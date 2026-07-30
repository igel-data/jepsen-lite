(ns sqlite.db
  "SQLite itself: a pool of connections on one database file, and the operations
   the workloads need.

   This namespace is the whole of what is being verified, and it is deliberately
   the half that knows nothing about jepsen-lite, HTTP, or how it is deployed.
   Both shapes of test run exactly this code: `sqlite.in-process` calls it
   directly, and `sqlite.driver` puts it behind an HTTP API so it can be a
   process something is able to kill. If the two shapes ever disagreed, that
   would be the test's fault rather than SQLite's -- so there is one copy.

   ## Two things this file is really about
   ## ===================================
   ##
   ## 1. `BEGIN IMMEDIATE`, not `BEGIN DEFERRED`
   ##
   Every read-modify-write here takes the write lock *before* it reads. A
   deferred transaction that reads and then writes has to upgrade its lock, and
   in WAL mode that upgrade fails with SQLITE_BUSY_SNAPSHOT if anyone committed
   in between -- and `busy_timeout` does not apply to it, because no amount of
   waiting can give a stale snapshot back its future. Retrying only the write at
   that point silently loses an update; the transaction has to be rolled back
   and re-run from the read. `BEGIN IMMEDIATE` makes the whole situation
   impossible, which is why it is what a correct SQLite integration uses.

   ## 2. What each failure is *certain* about
   ##
   The distinction the checkers depend on, and the one worth getting exactly
   right:

     BEGIN IMMEDIATE hit SQLITE_BUSY   the lock was never taken, nothing ran
                                       -> a certain failure (`reject!`)
     the operation was refused         insufficient funds, a CAS mismatch
                                       -> a certain failure (`reject!`)
     COMMIT failed                     -> INDETERMINATE. See `commit!`.

   Saying \"it failed\" where the honest answer is \"nobody knows\" would be
   lying to the checker, and a checker that has been lied to can prove anything.
   Both callers translate these the same way -- into a 409 and a 500, or into
   `lite.client/fail!` and `lite.client/info!`."
  (:require [clojure.java.io :as io])
  (:import (java.sql Connection DriverManager PreparedStatement ResultSet
                     SQLException)
           (java.util.concurrent LinkedBlockingQueue)))

;; ---- JDBC plumbing --------------------------------------------------------

(defn- prepare
  ^PreparedStatement [^Connection conn ^String sql params]
  (let [ps (.prepareStatement conn sql)]
    (dorun (map-indexed (fn [i p] (.setObject ps (inc i) p)) params))
    ps))

(defn- update!
  "Runs a statement and returns how many rows it changed."
  [^Connection conn sql & params]
  (with-open [ps (prepare conn sql params)]
    (.executeUpdate ps)))

(defn- rows
  "Runs a query and returns a vector of `(row-fn result-set)` per row."
  [^Connection conn sql params row-fn]
  (with-open [ps (prepare conn sql params)
              rs (.executeQuery ps)]
    (loop [acc (transient [])]
      (if (.next ^ResultSet rs)
        (recur (conj! acc (row-fn rs)))
        (persistent! acc)))))

(defn- one-long
  "The first column of the first row as a long, or nil if there was no row."
  [conn sql params]
  (first (rows conn sql params (fn [^ResultSet rs] (.getLong rs 1)))))

(defn- exec!
  "A statement with no parameters -- BEGIN, COMMIT, PRAGMA, DDL."
  [^Connection conn ^String sql]
  (with-open [s (.createStatement conn)]
    (.execute s sql)))

;; ---- the connection pool ---------------------------------------------------
;;
;; Several connections, not one. A single shared connection would serialize
;; every request inside JDBC, and the question these workloads exist to ask --
;; what concurrent transactions against one database file do to each other --
;; would never actually be put to SQLite.

(def ^:private pool-size 8)

(def ^:private schema
  ["CREATE TABLE IF NOT EXISTS registers (k INTEGER PRIMARY KEY, v INTEGER)"
   "CREATE TABLE IF NOT EXISTS elements  (e INTEGER PRIMARY KEY)"
   "CREATE TABLE IF NOT EXISTS counters  (k TEXT    PRIMARY KEY, v INTEGER NOT NULL)"
   "CREATE TABLE IF NOT EXISTS accounts  (id INTEGER PRIMARY KEY, balance INTEGER NOT NULL)"])

(def ^:private default-pragmas
  {:journal            "WAL"
   ;; FULL, and under `:power-off` this is finally load-bearing: lazyfs holds
   ;; writes until an fsync, so what SQLite never synced is what a power-off
   ;; drops. Under `:crash` it still proves nothing -- a SIGKILL leaves the page
   ;; cache alone, and FULL and OFF come through it identically. `sync=off` on
   ;; the command line is how you see the difference.
   :synchronous        "FULL"
   ;; So BEGIN IMMEDIATE waits for the write lock instead of failing the instant
   ;; another connection holds it. Every workload here is write-heavy on one
   ;; file; without this, most of the history would be lock contention.
   :busy-timeout       5000
   ;; Small, so checkpoints actually happen between faults. Otherwise every
   ;; recovery would be WAL replay and the main database file -- the other half
   ;; of SQLite's durability story -- would never be the thing recovered from.
   :wal-autocheckpoint 64})

(defn- open-connection
  ^Connection [^String url {:keys [journal synchronous busy-timeout
                                   wal-autocheckpoint]}]
  (let [conn (DriverManager/getConnection url)]
    ;; `journal_mode` is a property of the database file and persists across
    ;; opens; the others are per-connection and have to be set on every one.
    (exec! conn (str "PRAGMA journal_mode = " journal))
    (exec! conn (str "PRAGMA synchronous = " synchronous))
    (exec! conn (str "PRAGMA busy_timeout = " busy-timeout))
    (exec! conn (str "PRAGMA wal_autocheckpoint = " wal-autocheckpoint))
    conn))

(defn open-pool!
  "Opens `pool-size` connections on `db-file` and makes sure the tables exist.

   `CREATE TABLE IF NOT EXISTS`, and nothing else: opening must *attach* to
   whatever is on disk, never create or reset it. A store that came back empty
   after a crash would pass a durability test by forgetting the question."
  [db-file opts]
  (Class/forName "org.sqlite.JDBC")
  (io/make-parents db-file)
  (let [url     (str "jdbc:sqlite:" db-file)
        pragmas (merge default-pragmas opts)
        conns   (mapv (fn [_] (open-connection url pragmas)) (range pool-size))
        queue   (LinkedBlockingQueue. ^java.util.Collection conns)]
    (doseq [ddl schema] (exec! (first conns) ddl))
    {:queue queue, :connections conns}))

(defn close-pool!
  "Closes every connection.

   In the `:local-process` shape this is only ever reached on a clean shutdown
   -- which is exactly the path a `kill -9` does not take. In the `:in-process`
   shape it is what the crash nemesis *is*, and the difference between the two
   is the whole reason both exist: closing the last connection to a WAL database
   checkpoints it and removes the log, so what reopens afterwards is a
   checkpointed database file. A killed process leaves the log behind, and
   reopening has to replay it."
  [{:keys [connections]}]
  (doseq [^Connection c connections]
    (try (.close c) (catch SQLException _ nil))))

(defn- borrow ^Connection [pool] (.take ^LinkedBlockingQueue (:queue pool)))
(defn- give-back [pool conn] (.put ^LinkedBlockingQueue (:queue pool) conn))

(defmacro ^:private with-conn
  "One connection from the pool, for a single autocommit statement."
  [[sym pool] & body]
  `(let [pool# ~pool
         ~sym  (borrow pool#)]
     (try ~@body (finally (give-back pool# ~sym)))))

;; ---- outcomes --------------------------------------------------------------

(def ^:private rejected-key ::rejected)
(def ^:private commit-failed-key ::commit-failed)

(defn reject!
  "The operation certainly did not take effect: SQLite refused it, or the
   transaction that would have carried it out was rolled back. Callers turn this
   into an ordinary `:fail`."
  [reason]
  (throw (ex-info (str "rejected: " reason) {rejected-key reason})))

(defn rejection
  "The reason a throwable carries, if it is a rejection -- nil otherwise."
  [t]
  (when (instance? clojure.lang.IExceptionInfo t)
    (get (ex-data t) rejected-key)))

(defn commit-failed?
  "Did this throwable come from a COMMIT whose outcome nobody can see? Callers
   turn this into `:info`, never into a failure."
  [t]
  (boolean (when (instance? clojure.lang.IExceptionInfo t)
             (get (ex-data t) commit-failed-key))))

(def ^:private contention-codes
  "SQLite's ways of saying someone else has the lock. `SQLITE_BUSY_SNAPSHOT` is
   the WAL-specific one: this connection's read snapshot is too old to be
   upgraded to a write."
  #{"SQLITE_BUSY" "SQLITE_BUSY_SNAPSHOT" "SQLITE_LOCKED"})

(defn- contention?
  "Did this exception come from another connection holding the lock, rather than
   from anything being wrong? The result-code name is in the message --
   `[SQLITE_BUSY] The database file is locked ...` -- on every sqlite-jdbc
   exception, which is a more stable thing to match on than the numeric code."
  [^Throwable t]
  (boolean (some-> (ex-message t)
                   (->> (re-find #"SQLITE_[A-Z_]+"))
                   contention-codes)))

;; ---- transactions ----------------------------------------------------------

(defn- rollback-quietly!
  "Rolls back if there is anything to roll back. SQLite answers `cannot
   rollback - no transaction is active` when there isn't, which is not a problem
   worth propagating: the point is only that no connection ever goes back to the
   pool still holding a lock."
  [^Connection conn]
  (try (exec! conn "ROLLBACK") (catch SQLException _ nil)))

(defn- begin!
  "`BEGIN IMMEDIATE`: take the write lock now, before reading anything.

   If someone else holds it for longer than `busy_timeout`, the transaction
   never started at all -- so whatever it was going to do certainly did not
   happen, and that is a plain rejection rather than an unknown."
  [^Connection conn]
  (try
    (exec! conn "BEGIN IMMEDIATE")
    (catch SQLException e
      (if (contention? e)
        (reject! "busy: could not take the write lock")
        (throw e)))))

(defn- commit!
  "`COMMIT`, and if it fails, say so honestly.

   A failed COMMIT is the one place in this file where the answer is genuinely
   unknown, and it is deliberately *not* turned into a rejection. Rolling back
   afterwards and calling it a certain failure would be a guess dressed up as a
   fact; the rollback can itself fail, and an I/O error at commit can leave the
   transaction in a state neither side can see into. `:info` is what the
   checkers are built to handle, and it costs the run nothing."
  [^Connection conn]
  (try
    (exec! conn "COMMIT")
    (catch SQLException e
      (rollback-quietly! conn)
      (throw (ex-info (str "commit failed, outcome unknown: " (ex-message e))
                      {commit-failed-key true}
                      e)))))

(defn- transact
  "Runs `(f conn)` between `BEGIN IMMEDIATE` and `COMMIT`, and returns whatever
   it returned. A rejection from inside the body rolls back first, so it really
   is certain by the time the caller hears about it.

   The connection is rolled back before it goes back to the pool whatever
   happened: one left holding the write lock would stall every other worker
   until its `busy_timeout` expired, and the run would be measuring this
   function rather than SQLite."
  [pool f]
  (let [conn (borrow pool)]
    (try
      (begin! conn)
      (let [result (try
                     (f conn)
                     (catch Throwable t
                       (rollback-quietly! conn)
                       (throw t)))]
        (commit! conn)
        result)
      (finally
        (rollback-quietly! conn)
        (give-back pool conn)))))

;; ---- register -- independent linearizable registers ------------------------
;;
;; No transactions here, and that is the point: SQLite wraps every single
;; statement in an implicit transaction, so a one-statement CAS is atomic
;; already. Building it out of BEGIN/SELECT/UPDATE/COMMIT would be strictly more
;; code doing strictly the same thing, more slowly.

(defn register-read
  [pool k]
  (with-conn [c pool]
    (one-long c "SELECT v FROM registers WHERE k = ?" [k])))

(defn register-write
  [pool k v]
  (with-conn [c pool]
    (update! c (str "INSERT INTO registers (k, v) VALUES (?, ?) "
                    "ON CONFLICT(k) DO UPDATE SET v = excluded.v")
             k v)
    v))

(defn register-cas
  "Compare-and-set as one statement. `WHERE v = ?` is the compare and `SET v` is
   the set, and SQLite runs them as one atomic statement -- nobody can slip in
   between. No rows changed means the register did not hold `old`: an ordinary
   mismatch, and a history full of them is still linearizable."
  [pool k old new]
  (with-conn [c pool]
    (if (pos? (update! c "UPDATE registers SET v = ? WHERE k = ? AND v = ?"
                       new k old))
      new
      (reject! "cas mismatch"))))

;; ---- set -- durability of acknowledged writes ------------------------------
;;
;; One row per element, so adds never contend for the same row. They still
;; serialize on SQLite's single write lock, which is what `busy_timeout` is for.

(defn set-add
  [pool element]
  (with-conn [c pool]
    (update! c "INSERT INTO elements (e) VALUES (?)" element)
    element))

(defn set-read
  [pool]
  (with-conn [c pool]
    (rows c "SELECT e FROM elements ORDER BY e" []
          (fn [^ResultSet rs] (.getLong rs 1)))))

;; ---- counter -- read-modify-write under contention -------------------------
;;
;; Written as a real read-then-write transaction rather than the atomic
;; `SET v = v + ?` one-liner, because the read-modify-write *is* the pattern
;; under test: it is how applications actually increment things, and it is the
;; shape that goes wrong under `BEGIN DEFERRED`.

(def ^:private counter-key "counter")

(defn counter-add
  [pool amount]
  (transact pool
            (fn [c]
              (let [total (+ (or (one-long c "SELECT v FROM counters WHERE k = ?"
                                           [counter-key])
                                 0)
                             amount)]
                (update! c (str "INSERT INTO counters (k, v) VALUES (?, ?) "
                                "ON CONFLICT(k) DO UPDATE SET v = excluded.v")
                         counter-key total)
                total))))

(defn counter-read
  [pool]
  (with-conn [c pool]
    (or (one-long c "SELECT v FROM counters WHERE k = ?" [counter-key]) 0)))

;; ---- bank -- multi-key atomic transactions ---------------------------------
;;
;; The interesting one. A transfer has to debit one account and credit another
;; as one step, and a read that catches it half-done sees a total that doesn't
;; add up -- which is precisely what the checker looks for.

(defn bank-init
  "The opening balances, from the workload's first phase. Every account is
   named, so a read covers them all from the very first one."
  [pool balances]
  (transact pool
            (fn [c]
              (doseq [[id balance] balances]
                (update! c (str "INSERT INTO accounts (id, balance) VALUES (?, ?) "
                                "ON CONFLICT(id) DO UPDATE SET balance = excluded.balance")
                         id balance))
              balances)))

(defn bank-read
  "Every balance, from one statement. SQLite runs it as a single implicit
   transaction, so it cannot see a transfer with one leg applied."
  [pool]
  (with-conn [c pool]
    (into {} (rows c "SELECT id, balance FROM accounts" []
                   (fn [^ResultSet rs] [(.getLong rs 1) (.getLong rs 2)])))))

(defn bank-transfer
  [pool from to amount]
  (transact pool
            (fn [c]
              (let [fb (one-long c "SELECT balance FROM accounts WHERE id = ?" [from])
                    tb (one-long c "SELECT balance FROM accounts WHERE id = ?" [to])]
                (when (or (nil? fb) (nil? tb))
                  (reject! "no such account"))
                (when (< fb amount)
                  (reject! "insufficient funds"))
                (update! c "UPDATE accounts SET balance = ? WHERE id = ?"
                         (- fb amount) from)
                (update! c "UPDATE accounts SET balance = ? WHERE id = ?"
                         (+ tb amount) to)
                amount))))

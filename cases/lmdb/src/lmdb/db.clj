(ns lmdb.db
  "LMDB itself: one environment on one directory, and the operations the
   workloads need.

   This namespace is the whole of what is being verified, and it is deliberately
   the half that knows nothing about jepsen-lite, HTTP, or how it is deployed.
   Both shapes of test run exactly this code: `lmdb.in-process` calls it
   directly, and `lmdb.driver` puts it behind an HTTP API so it can be a process
   something is able to kill. If the two shapes ever disagreed, that would be
   the test's fault rather than LMDB's -- so there is one copy.

   ## Three things this file is really about
   ## =====================================
   ##
   ## 1. There is exactly one writer, and it is exclusive from the start
   ##
   `txnWrite` takes LMDB's single writer lock and blocks until it has it. There
   is no lock to upgrade, so none of the read-then-write hazards that a
   deferred-transaction database has can arise here: every read-modify-write in
   this file -- the CAS, the counter, the transfer -- is serializable by
   construction, and the price is that writes do not run concurrently at all.
   That is LMDB's central trade, and these workloads are how you see it.

   Reads never block and are never blocked: a read transaction is a snapshot,
   so `bank-read` sees every account as of one instant and can never catch a
   transfer with one leg applied. Taking the accounts in separate transactions
   instead would be the way to get that wrong.

   ## 2. A crash needs no recovery
   ##
   LMDB is copy-on-write with two meta pages, and a commit ends by flipping to
   whichever one it just wrote. There is no log and no replay: a killed process
   leaves a database whose last valid meta page is the last committed
   transaction, and opening it is not a recovery so much as a read. That is the
   claim `set kill` exists to put under load.

   ## 3. What each failure is *certain* about
   ##
   The distinction the checkers depend on:

     the operation was refused     a CAS mismatch, insufficient funds
                                   -> a certain failure (`reject!`)
     the map is full               the transaction aborted, nothing ran
                                   -> a certain failure (`reject!`)
     the environment was closed    the crash nemesis got here first; we never
                                   reached LMDB at all
                                   -> a certain failure (`reject!`)
     commit failed                 -> INDETERMINATE. See `commit!`.

   Saying \"it failed\" where the honest answer is \"nobody knows\" would be
   lying to the checker, and a checker that has been lied to can prove anything."
  (:require [clojure.java.io :as io]
            ;; Before the imports below: this is what tells lmdbjava where the
            ;; native library is, and it has to happen first.
            [lmdb.native])
  (:import (java.nio ByteBuffer ByteOrder)
           (java.util.concurrent.locks ReentrantReadWriteLock)
           (org.lmdbjava CursorIterable CursorIterable$KeyVal Dbi Dbi$DbFullException
                         DbiFlags Env Env$MapFullException Env$ReadersFullException
                         EnvFlags KeyRange PutFlags Txn Txn$TxFullException)))

(def ^:private ^"[Lorg.lmdbjava.PutFlags;" no-flags
  "`Dbi.put` is varargs, and the flags it takes are all things this suite wants
   none of -- no append hints, no duplicate handling, no reserve. One empty
   array, made once."
  (into-array PutFlags []))

;; ---- keys and values -------------------------------------------------------
;;
;; The workloads speak longs; LMDB speaks bytes, ordered bytewise. Eight bytes
;; big-endian, so that lexicographic order is numeric order and a cursor walk of
;; `elements` comes back in the order the workload added them.

(def ^:private long-bytes 8)

(defn- ^ByteBuffer put-long
  [^ByteBuffer buf ^long n]
  (doto buf (.putLong 0 n)))

(defn- ^ByteBuffer long->buf
  [^long n]
  (-> (ByteBuffer/allocateDirect long-bytes)
      (.order ByteOrder/BIG_ENDIAN)
      (put-long n)))

(defn- ^ByteBuffer string->buf
  [^String s]
  (let [bytes (.getBytes s "UTF-8")
        buf   (ByteBuffer/allocateDirect (alength bytes))]
    (.put buf bytes)
    (.flip buf)
    buf))

(defn- buf->long
  [^ByteBuffer buf]
  (when buf (.getLong (.order buf ByteOrder/BIG_ENDIAN) 0)))

;; ---- the environment -------------------------------------------------------

(def ^:private map-size
  "1 GiB. LMDB maps the whole thing up front and the file is sparse, so this
   costs nothing until it is used -- and running out mid-run would end the test
   for a reason that has nothing to do with what is being asked."
  (* 1024 1024 1024))

(def ^:private max-readers 256)

(def ^:private dbi-names ["registers" "elements" "counters" "accounts"])

(defn- open-env
  ^Env [dir {:keys [sync?]}]
  (let [flags (cond-> [EnvFlags/MDB_NOTLS]
                ;; MDB_NOTLS is not optional here: without it a read
                ;; transaction is pinned to the thread that opened it, and a
                ;; pool of request threads leaks reader slots until there are
                ;; none left.
                (false? sync?) (conj EnvFlags/MDB_NOSYNC))]
    (-> (Env/create)
        (.setMapSize map-size)
        (.setMaxDbs (count dbi-names))
        (.setMaxReaders max-readers)
        (.open (io/file dir) (into-array EnvFlags flags)))))

(defn open-env!
  "Opens the environment on `dir` and returns the store handle.

   `MDB_CREATE` on each named database, and nothing else: opening must *attach*
   to whatever is on disk, never create or reset it. A store that came back
   empty after a crash would pass a durability test by forgetting the question.

   The lock is what makes closing safe. LMDB is native code, and closing an
   environment out from under a thread that is inside it is undefined behaviour
   -- a segfault, not an exception. Operations hold the shared half; `close-env!`
   takes the exclusive half and so waits for whatever is in flight."
  [dir opts]
  (.mkdirs (io/file dir))
  (let [env  (open-env dir opts)
        dbis (into {} (map (fn [n]
                             [n (.openDbi env ^String n
                                          (into-array DbiFlags
                                                      [DbiFlags/MDB_CREATE]))]))
                   dbi-names)]
    {:env env, :dbis dbis, :lock (ReentrantReadWriteLock.), :closed? (atom false)}))

(defn close-env!
  "Closes the environment, once every operation still inside it has come out.

   In the `:local-process` shape this is only ever reached on a clean shutdown
   -- which is exactly the path a `kill -9` does not take. In the `:in-process`
   shape it is what the crash nemesis *is*, and for LMDB the two turn out to
   prove nearly the same thing: there is no log to flush and no checkpoint to
   take, so a clean close leaves the same last-committed database on disk that a
   killed process does. What `kill` adds is that the process really was killed
   mid-write, rather than politely waited for."
  [{:keys [^Env env lock closed?]}]
  (let [wl (.writeLock ^ReentrantReadWriteLock lock)]
    (.lock wl)
    (try
      (when (compare-and-set! closed? false true)
        (.close env))
      (finally (.unlock wl)))))

;; ---- outcomes --------------------------------------------------------------

(def ^:private rejected-key ::rejected)
(def ^:private commit-failed-key ::commit-failed)

(defn reject!
  "The operation certainly did not take effect: LMDB refused it, or the
   transaction that would have carried it out was aborted. Callers turn this
   into an ordinary `:fail`."
  [reason]
  (throw (ex-info (str "rejected: " reason) {rejected-key reason})))

(defn rejection
  "The reason a throwable carries, if it is a rejection -- nil otherwise."
  [t]
  (when (instance? clojure.lang.IExceptionInfo t)
    (get (ex-data t) rejected-key)))

(defn commit-failed?
  "Did this throwable come from a commit whose outcome nobody can see? Callers
   turn this into `:info`, never into a failure."
  [t]
  (boolean (when (instance? clojure.lang.IExceptionInfo t)
             (get (ex-data t) commit-failed-key))))

(defn- aborted?
  "Did LMDB refuse in a way that leaves the transaction unstarted or rolled
   back? A full map, a full database, a transaction with more dirty pages than
   it can hold, an exhausted reader table -- in every one of them the operation
   certainly did not take effect, so they are failures and not unknowns."
  [^Throwable t]
  (or (instance? Env$MapFullException t)
      (instance? Env$ReadersFullException t)
      (instance? Dbi$DbFullException t)
      (instance? Txn$TxFullException t)))

;; ---- transactions ----------------------------------------------------------

(defn- with-env
  "Runs `(f env dbis)` with the environment guaranteed open underneath it.

   An operation that arrives while the crash nemesis has the environment away
   -- either closed under us, or not yet replaced, which jepsen-lite signals by
   handing over a nil conn -- is refused rather than run. We never reached LMDB,
   so it certainly did not happen, and a `:fail` says exactly that. Letting it
   fall through to a NullPointerException would report the same window as an
   indeterminate op, which throws away something we know."
  [{:keys [env dbis lock closed?] :as store} f]
  (when (nil? store)
    (reject! "there is no environment: the target is mid-crash"))
  (let [rl (.readLock ^ReentrantReadWriteLock lock)]
    (.lock rl)
    (try
      (when @closed?
        (reject! "the environment is closed"))
      (f env dbis)
      (finally (.unlock rl)))))

(defn- read-txn
  "Runs `(f txn dbis)` inside a read transaction -- one snapshot, of everything
   it looks at. Closed promptly whatever happens: LMDB cannot reuse the pages an
   open reader still refers to, so a long-lived read transaction makes the file
   grow rather than making anything wrong."
  [store f]
  (with-env store
    (fn [^Env env dbis]
      (with-open [txn (.txnRead env)]
        (f txn dbis)))))

(defn- write-txn
  "Runs `(f txn dbis)` inside a write transaction and commits it.

   `txnWrite` blocks until LMDB's single writer lock is free, so this is where
   every writing worker queues up. Nothing is refused for contention -- there is
   no busy error to handle, because waiting is the whole mechanism.

   A rejection from inside the body aborts the transaction on the way out (the
   `with-open` does it, since `commit` never ran), so it really is certain by
   the time the caller hears about it. A failure from `commit` itself is the one
   thing that isn't -- see below."
  [store f]
  (with-env store
    (fn [^Env env dbis]
      (with-open [txn (.txnWrite env)]
        (let [result (f txn dbis)]
          ;; Not a rejection, whatever went wrong. LMDB's commit writes the data
          ;; pages and then flips the meta page, and a failure part-way through
          ;; is exactly the case nobody outside can distinguish: the transaction
          ;; may have become durable or may not. `:info` is what the checkers
          ;; are built to handle, and it costs the run nothing.
          (try
            (.commit ^Txn txn)
            (catch Throwable t
              (if (aborted? t)
                (reject! (str "aborted: " (.getName (class t))))
                (throw (ex-info (str "commit failed, outcome unknown: "
                                     (ex-message t))
                                {commit-failed-key true}
                                t)))))
          result)))))

(defn- guarded
  "Turns LMDB's own certain refusals into rejections, wherever they surface."
  [f]
  (try
    (f)
    (catch Throwable t
      (if (aborted? t)
        (reject! (str "aborted: " (.getName (class t))))
        (throw t)))))

;; ---- register -- independent linearizable registers ------------------------

(defn register-read
  [store k]
  (read-txn store
            (fn [txn dbis]
              (buf->long (.get ^Dbi (dbis "registers") txn (long->buf k))))))

(defn register-write
  [store k v]
  (guarded
   #(write-txn store
               (fn [txn dbis]
                 (.put ^Dbi (dbis "registers") txn (long->buf k) (long->buf v)
                       no-flags)
                 v))))

(defn register-cas
  "Compare-and-set built from a write transaction, because LMDB has no CAS of
   its own: read the key, compare, put. There is only one writer, so nobody can
   slip in between -- the exclusion is LMDB's, not something arranged here. A
   mismatch is an ordinary failed op, and a history full of them is still
   linearizable."
  [store k old new]
  (guarded
   #(write-txn store
               (fn [txn dbis]
                 (let [dbi ^Dbi (dbis "registers")]
                   (if (= old (buf->long (.get dbi txn (long->buf k))))
                     (do (.put dbi txn (long->buf k) (long->buf new) no-flags)
                         new)
                     (reject! "cas mismatch")))))))

;; ---- set -- durability of acknowledged writes ------------------------------

(defn set-add
  [store element]
  (guarded
   #(write-txn store
               (fn [txn dbis]
                 (.put ^Dbi (dbis "elements") txn (long->buf element)
                       (long->buf element) no-flags)
                 element))))

(defn set-read
  "A cursor walk of the whole database, in key order -- which is numeric order,
   because the keys are big-endian."
  [store]
  (read-txn store
            (fn [txn dbis]
              (with-open [it ^CursorIterable (.iterate ^Dbi (dbis "elements")
                                                       txn (KeyRange/all))]
                (into [] (map (fn [^CursorIterable$KeyVal kv]
                                (buf->long (.key kv))))
                      it)))))

;; ---- counter -- read-modify-write ------------------------------------------

(def ^:private counter-key "counter")

(defn counter-add
  [store amount]
  (guarded
   #(write-txn store
               (fn [txn dbis]
                 (let [dbi   ^Dbi (dbis "counters")
                       total (+ (or (buf->long (.get dbi txn
                                                     (string->buf counter-key)))
                                    0)
                                amount)]
                   (.put dbi txn (string->buf counter-key) (long->buf total) no-flags)
                   total)))))

(defn counter-read
  [store]
  (read-txn store
            (fn [txn dbis]
              (or (buf->long (.get ^Dbi (dbis "counters") txn
                                   (string->buf counter-key)))
                  0))))

;; ---- bank -- multi-key atomic transfers ------------------------------------

(defn bank-init
  "The opening balances, from the workload's first phase. Every account is
   named, so a read covers them all from the very first one."
  [store balances]
  (guarded
   #(write-txn store
               (fn [txn dbis]
                 (let [dbi ^Dbi (dbis "accounts")]
                   (doseq [[id balance] balances]
                     (.put dbi txn (long->buf id) (long->buf balance) no-flags))
                   balances)))))

(defn bank-read
  "Every balance, from one read transaction -- one snapshot, so it cannot see a
   transfer with one leg applied. Reading the accounts one at a time, each in
   its own transaction, is precisely the mistake this is written to avoid."
  [store]
  (read-txn store
            (fn [txn dbis]
              (let [dbi ^Dbi (dbis "accounts")]
                (with-open [it ^CursorIterable (.iterate dbi txn (KeyRange/all))]
                  (into {} (map (fn [^CursorIterable$KeyVal kv]
                                  [(buf->long (.key kv))
                                   (buf->long (.val kv))]))
                        it))))))

(defn bank-transfer
  [store from to amount]
  (guarded
   #(write-txn store
               (fn [txn dbis]
                 (let [dbi ^Dbi (dbis "accounts")
                       fb  (buf->long (.get dbi txn (long->buf from)))
                       tb  (buf->long (.get dbi txn (long->buf to)))]
                   (when (or (nil? fb) (nil? tb))
                     (reject! "no such account"))
                   (when (< fb amount)
                     (reject! "insufficient funds"))
                   (.put dbi txn (long->buf from) (long->buf (- fb amount)) no-flags)
                   (.put dbi txn (long->buf to) (long->buf (+ tb amount)) no-flags)
                   amount)))))

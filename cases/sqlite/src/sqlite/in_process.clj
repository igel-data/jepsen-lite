(ns sqlite.in-process
  "The `:in-process` half: a jepsen-lite ClientAdapter that opens SQLite inside
   jepsen-lite's own JVM, and one handler per workload.

   Compare it with `sqlite.client`. The handlers there make an HTTP request;
   these call `sqlite.db` directly. Everything else -- which workloads exist,
   what each `:f` means, what the checkers do, what counts as certain -- is
   identical, because the protocol a target speaks and the way it is deployed
   are separate concerns in jepsen-lite. This namespace is one protocol; the
   target-type is the other axis, and `sqlite.runner` picks it.

   ## What a crash means here
   ##
   `open` opens a pool on a database file; `close` closes it; the crash nemesis
   is `close` then `open`. That is a **clean** shutdown and recovery, and it is
   worth being exact about what it proves, because a crash test that proves less
   than you think is worse than none:

   Closing the last connection to a WAL database checkpoints it and removes the
   log, so what reopens afterwards is a checkpointed database file. That
   exercises the checkpoint path and reopening, and it does not touch the
   process boundary at all. Replaying an uncheckpointed WAL that a dead process
   left behind is the *other* path, and it needs a real SIGKILL -- which is what
   `sqlite.driver` and the `:local-process` shape exist for.

   Ops that land in the moment between the close and the open find no instance
   and are recorded as `:info`, which is the honest answer."
  (:require [lite.client :as client]
            [sqlite.db :as db]))

;; ---- outcomes --------------------------------------------------------------

(defn- signalling
  "Wraps a handler so that `sqlite.db`'s outcomes reach jepsen-lite as the right
   kind of thing.

   This is the same translation `sqlite.driver` makes into 409 and 500, done
   without a wire in between: a refusal is a certain `:fail`, and a COMMIT whose
   outcome nobody can see is `:info`. Doing it explicitly rather than letting
   `client/complete` treat both as unexpected exceptions is the whole point --
   a CAS mismatch reported as `:info` would leave every history full of
   indeterminate ops that the checkers then have to assume the worst about."
  [handler]
  (fn [pool op]
    (try
      (handler pool op)
      (catch clojure.lang.ExceptionInfo e
        (cond
          (db/rejection e)     (client/fail! (db/rejection e))
          (db/commit-failed? e) (client/info! {:commit-failed (ex-message e)})
          :else                 (throw e))))))

;; ---- the adapter -----------------------------------------------------------

(defrecord Adapter [handler db-file journal]
  client/ClientAdapter
  (open [_]
    ;; Opens (recovers) the database from a fixed file. Note what is absent:
    ;; nothing is created or reset here beyond `CREATE TABLE IF NOT EXISTS`.
    ;; The data on disk is the durable store, and it survives a close/reopen --
    ;; which is exactly what the crash nemesis does. jepsen-lite keeps at most
    ;; one conn live at a time, so there are never two pools on one file.
    (db/open-pool! db-file {:journal (or journal "WAL")}))

  (invoke [_ conn op]
    (client/complete handler conn op))

  (close [_ conn]
    (when conn (db/close-pool! conn))))

;; ---- handlers --------------------------------------------------------------
;;
;; Each returns what the workload's checker reads off the completed op, which is
;; not always what SQLite hands back -- see `:counter` and `:register`.

(defn- register-handler
  [pool {:keys [f key value]}]
  (case f
    :read  (db/register-read pool key)
    :write (do (db/register-write pool key value)
               value)
    :cas   (let [[old new] value]
             ;; A mismatch is a `reject!`, which `signalling` turns into an
             ;; ordinary failed op rather than a violation. On success return
             ;; the pair the op carried: Knossos's model reads `[old new]` off
             ;; the completed op.
             (db/register-cas pool key old new)
             value)))

(defn- set-handler
  [pool {:keys [f value]}]
  (case f
    :add  (db/set-add pool value)
    :read (db/set-read pool)))

(defn- counter-handler
  [pool {:keys [f value]}]
  (case f
    ;; Return the increment, not the running total SQLite hands back: the
    ;; checker sums the amounts on the completed ops.
    :add  (do (db/counter-add pool value)
              value)
    :read (long (db/counter-read pool))))

(defn- bank-handler
  [pool {:keys [f value]}]
  (case f
    ;; The opening balances, from the workload's first phase, through the same
    ;; handler as every other op. The handler doesn't know they are special, and
    ;; SQLite doesn't know it is a bank.
    :init     (db/bank-init pool value)
    :read     (db/bank-read pool)
    :transfer (let [{:keys [from to amount]} value]
                (db/bank-transfer pool from to amount))))

(def handlers
  "Workload -> the handler that calls SQLite directly for it."
  (update-vals {:register register-handler
                :bank     bank-handler
                :set      set-handler
                :counter  counter-handler}
               signalling))

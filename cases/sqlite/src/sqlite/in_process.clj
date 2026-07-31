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
            [lite.handlers :as handlers]
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

(defn adapter
  "An adapter which opens and recovers one fixed SQLite database file."
  [{:keys [db-file journal]}]
  (client/adapter
   {:open  #(db/open-pool! db-file {:journal (or journal "WAL")})
    :close db/close-pool!}))

;; ---- handlers --------------------------------------------------------------
;;
;; Each returns what the workload's checker reads off the completed op, which is
;; not always what SQLite hands back -- see `:counter` and `:register`.

(def handlers
  "Workload -> the handler that calls SQLite directly for it."
  (update-vals {:register (handlers/register
                           {:read db/register-read
                            :write db/register-write
                            :cas db/register-cas})
                :bank     (handlers/bank
                           {:init db/bank-init
                            :read db/bank-read
                            :transfer db/bank-transfer})
                :set      (handlers/set
                           {:add db/set-add
                            :read db/set-read})
                :counter  (handlers/counter
                           {:add db/counter-add
                            :read db/counter-read})}
               signalling))

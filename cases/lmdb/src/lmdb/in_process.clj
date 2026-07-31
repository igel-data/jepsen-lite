(ns lmdb.in-process
  "The `:in-process` half: a jepsen-lite ClientAdapter that opens LMDB inside
   jepsen-lite's own JVM, and one handler per workload.

   Compare it with `lmdb.client`. The handlers there make an HTTP request; these
   call `lmdb.db` directly. Everything else -- which workloads exist, what each
   `:f` means, what the checkers do, what counts as certain -- is identical,
   because the protocol a target speaks and the way it is deployed are separate
   concerns in jepsen-lite. This namespace is one protocol; the target-type is
   the other axis, and `lmdb.runner` picks it.

   ## What a crash means here
   ##
   `open` opens an environment on a directory; `close` closes it; the crash
   nemesis is `close` then `open`. That is a **clean** shutdown, and it waits for
   whatever operations are inside LMDB to come out first -- it has to, because
   closing a native environment out from under a thread that is in it is a
   segfault rather than an exception.

   For LMDB that turns out to prove nearly as much as the violent version, which
   is not true of a database with a log. There is no checkpoint to take and no
   journal to flush: a commit ends by flipping a meta page, so what is on disk
   after a clean close is the same last-committed database that a killed process
   leaves behind. What `kill` adds -- and it is worth having -- is that the
   process really was stopped mid-write rather than politely waited for.

   Ops that land after the close and before the open are refused rather than
   guessed at: `lmdb.db` checks before it touches LMDB, so it knows they did not
   happen."
  (:require [lite.client :as client]
            [lite.handlers :as handlers]
            [lmdb.db :as db]))

;; ---- outcomes --------------------------------------------------------------

(defn- signalling
  "Wraps a handler so that `lmdb.db`'s outcomes reach jepsen-lite as the right
   kind of thing.

   This is the same translation `lmdb.driver` makes into 409 and 500, done
   without a wire in between: a refusal is a certain `:fail`, and a commit whose
   outcome nobody can see is `:info`. Doing it explicitly rather than letting
   `client/complete` treat both as unexpected exceptions is the whole point -- a
   CAS mismatch reported as `:info` would leave every history full of
   indeterminate ops that the checkers then have to assume the worst about."
  [handler]
  (fn [store op]
    (try
      (handler store op)
      (catch clojure.lang.ExceptionInfo e
        (cond
          (db/rejection e)      (client/fail! (db/rejection e))
          (db/commit-failed? e) (client/info! {:commit-failed (ex-message e)})
          :else                 (throw e))))))

;; ---- the adapter -----------------------------------------------------------

(defn adapter
  "An adapter which opens and recovers one fixed LMDB environment."
  [{:keys [dir sync?]}]
  (client/adapter
   {:open  #(db/open-env! dir {:sync? sync?})
    :close db/close-env!}))

;; ---- handlers --------------------------------------------------------------
;;
;; Each returns what the workload's checker reads off the completed op, which is
;; not always what LMDB hands back -- see `:counter` and `:register`.

(def handlers
  "Workload -> the handler that calls LMDB directly for it."
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

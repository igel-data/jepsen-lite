(ns lite.core
  "The runner: config in, verdict out.

   A run is Jepsen's own pipeline with the parts a Lite user shouldn't have to
   see kept internal -- generator -> the user's adapter (via the bridge) ->
   history -> the workload's checker."
  (:require [clojure.pprint :as pprint]
            [jepsen.core :as jepsen]
            [jepsen.generator :as gen]
            [jepsen.generator.interpreter :as interpreter]
            [jepsen.history :as h]
            [jepsen.store :as store]
            [jepsen.tests :as tests]
            [jepsen.util :as util]
            [lite.bridge :as bridge]
            [lite.nemesis :as nemesis]
            [lite.target]
            [lite.target.compose]
            [lite.target.http]
            [lite.target.in-process]
            [lite.target.local-process]
            [lite.workload :as workload]))

(def default-nodes
  "An in-process target is one logical node. Later target-types supply their
   own; nothing here branches on which."
  ["local"])

(def default-target
  {:type :in-process})

(defn validate!
  "Checks the combinations a run can't recover from -- an unrunnable
   target-type, or a fault that target-type can't inject -- before anything is
   built, opened or generated. Throws with an explanation; returns config."
  [{:keys [target nemesis]}]
  (let [target (or target default-target)]
    (lite.target/validate! target)
    ;; Two questions in order. First: can a target of this kind be given these
    ;; faults at all -- the axis-2 table, the same answer on every machine.
    ;; Then: can *this* machine carry them out, which is where a power-off asks
    ;; for Linux and FUSE. Answering them the other way round would tell a user
    ;; their host is wrong about a fault their target-type could never do.
    (nemesis/validate! (:type target) nemesis)
    (lite.target/verify-faults! target nemesis))
  nil)

(defn- check-concurrency!
  "Some workloads split workers into fixed-size groups and need a worker count
   that divides evenly. Say so plainly rather than letting an assertion fire
   from inside the generator."
  [workload-name {:keys [concurrency-multiple]} concurrency]
  (when (and concurrency-multiple
             (pos? concurrency-multiple)
             (not (zero? (mod concurrency concurrency-multiple))))
    (throw (ex-info
            (str "The " workload-name " workload can't run with :concurrency "
                 concurrency ".\n\n"
                 "  why: it works each key with a group of " concurrency-multiple
                 " threads, so the worker count has to divide into whole"
                 " groups.\n\n"
                 "  fix: use a multiple of " concurrency-multiple ", such as "
                 (* concurrency-multiple (max 1 (quot concurrency
                                                      concurrency-multiple)))
                 " or " (* concurrency-multiple
                           (inc (quot concurrency concurrency-multiple)))
                 ", or leave :concurrency out and let the workload choose.")
            {:lite/error  :invalid-concurrency
             :workload    workload-name
             :concurrency concurrency
             :multiple    concurrency-multiple})))
  concurrency)

(defn test-map
  "Builds the Jepsen test map for `config`. Everything not named here keeps
   `noop-test`'s defaults: a noop os/db is correct for an in-process target,
   which has no node to configure."
  [{:keys [adapter handler workload workload-opts concurrency time-limit name
           nodes target nemesis nemesis-opts]}]
  (let [nodes   (or nodes default-nodes)
        target  (or target default-target)
        w-name  (or workload :register)
        w       (workload/build w-name
                                (cond-> (assoc workload-opts :nodes nodes)
                                  ;; With a clock to run against, let the ops
                                  ;; run until time is up rather than stopping
                                  ;; at a workload's default op count.
                                  (and time-limit
                                       (not (contains? workload-opts :op-limit)))
                                  (assoc :op-limit false)))
        concurrency (check-concurrency!
                     w-name w (or concurrency (:concurrency w) (count nodes)))
        ;; `invoke` takes no handler argument, so the adapter carries it. The
        ;; config is the user-facing place to put it; bind it in here.
        adapter (cond-> adapter handler (assoc :handler handler))
        conn    (lite.target/build target adapter)
        nem     (nemesis/build (:type target) conn
                               (assoc nemesis-opts :intents nemesis))]
    (merge tests/noop-test
           ;; Whatever the workload's own generator and checker need to find in
           ;; the test map.
           (:test-opts w)
           {:pure-generators true
            :name            (or name "jepsen-lite")
            :nodes           nodes
            :concurrency     concurrency
            :client          (cond-> (bridge/client adapter conn)
                               (:wrap-client w) ((:wrap-client w)))
            :generator       (gen/phases
                              ;; One clock over both. The nemesis has to be
                              ;; inside the time limit, not beside it: a fault
                              ;; schedule that outlived the clients would keep
                              ;; a `:time-limit 5` run going for as long as it
                              ;; had faults left to inject.
                              (cond->> (:generator w)
                                nem        (gen/nemesis (:generator nem))
                                time-limit (gen/time-limit time-limit))

                              ;; Then undo whatever is still in force. A run
                              ;; that stopped mid-pause or mid-partition would
                              ;; otherwise take its closing reads against a
                              ;; target that can't answer.
                              (when (:final-generator nem)
                                (gen/nemesis (:final-generator nem)))

                              ;; And only now whatever the workload has to do
                              ;; last -- `:set`'s read of everything it added.
                              ;; It has to come after every fault, or it
                              ;; verifies a target the last faults never
                              ;; touched.
                              (when (:final-generator w)
                                (gen/clients (:final-generator w))))
            :checker         (:checker w)
            ;; `run` needs the target itself, not just the client wrapped
            ;; around it, to start and stop it around the run. It holds the
            ;; user's adapter, so like every other live object in the test map
            ;; it must be kept out of what gets written to the store.
            :lite/target     conn
            :nonserializable-keys #{:lite/target}}
           (when nem {:nemesis (:nemesis nem)}))))

(defn- realize-history
  "The history `with-history!` hands back reads its chunks from the store file
   on demand, so it dies once the store handle closes. Pull it into memory while
   the handle is still open, as an equivalent in-memory history."
  [history]
  (h/history (into [] history)
             {:already-ops? true, :have-indices? true, :dense-indices? true}))

(defn run
  "Runs `config`:

     {:adapter       <a ClientAdapter>
      :handler       (fn [conn op] ...) ; the user's op -> target-call mapping
      :workload      :register          ; optional; :register is the default
      :workload-opts {...}              ; optional; see the workload's ns
      :concurrency   <n>                ; optional; the workload picks otherwise
      :time-limit    <seconds>          ; optional; otherwise the run ends after
                                        ; the workload's op count
      :name          \"...\"            ; optional
      :target        {:type :in-process} ; optional; :in-process is the default
                                        ; see lite.target.<type> for its config
      :nemesis       [:crash]           ; optional; faults to inject
      :nemesis-opts  {...}}             ; optional; :in-process takes :crashes
                                        ; and :crash-interval, the others
                                        ; :faults (a cap) and :fault-interval;
                                        ; :compose also :fault-duration

   Interprets the workload's generator against the user's adapter -- with the
   nemesis, if any, perturbing the target as it goes -- checks the resulting
   history, and returns `{:valid? ..., :results ..., :history ...}`. The verdict
   is printed and, like the history, written to `store/`.

   `prepare-test` supplies the `:start-time`/`:concurrency`/`:barrier` the
   interpreter needs and makes the generator forgettable; the store handle has
   to be open for the interpreter's history writer, and stays open through
   analysis so the checker's results land next to the history.

   A target-type that runs the target -- `:local-process`, `:compose` -- starts
   it here and stops it in the `finally`, whatever the run did. The ones that
   don't own a target no-op."
  [config]
  (validate! config)
  (let [test    (jepsen/prepare-test (test-map config))
        target  (:lite/target test)
        _       (lite.target/start! target)
        test    (try
                  (store/with-handle [test test]
                    ;; save-0! writes the initial test; the history writer
                    ;; refuses to open without the block id it leaves in the
                    ;; test's metadata.
                    (let [test (store/save-0! test)
                          test (util/with-relative-time
                                 (store/with-history! [test test]
                                   (interpreter/run! test)))]
                      (-> test
                          (update :history realize-history)
                          store/save-1!
                          ;; Runs the checker and writes the results out.
                          jepsen/analyze!)))
                  (finally
                    (lite.target/stop! target)))
        results (:results test)]
    (println "\nVerdict:")
    (pprint/pprint results)
    {:valid?  (:valid? results)
     :results results
     :history (:history test)}))

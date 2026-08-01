(ns lite.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [lite.core :as core]
            [lite.runner :as runner]))

(defn- build-config [profile workload opts]
  {:profile profile, :workload workload, :opts opts})

(def sample-suite
  {:name              "sample"
   :workloads         [:register :set]
   :default-workloads :all
   :default-profile   :embedded
   :profiles
   {:embedded {:build #(build-config :embedded %1 %2)}
    :process  {:build #(build-config :process %1 %2)}}
   :options
   {:sync    {:values ["on" "off"]
              :parse #(= "on" %)
              :key :sync?
              :default "on"
              :doc "durability mode"}
    :data-dir {:key :data-dir}}})

(deftest parses-the-common-command-line
  (is (= {:action      :run
          :profile     :process
          :workloads   [:set]
          :faults      [:crash :pause]
          :time-limit  20
          :concurrency 8
          :options     {:sync? false, :data-dir "/tmp/db"}}
         (runner/parse-args
          sample-suite
          ["--profile" "process"
           "--workload" "set"
           "--fault" "crash"
           "--fault" "pause"
           "--time-limit" "20"
           "--concurrency" "8"
           "--sync" "off"
           "--data-dir" "/tmp/db"]))))

(deftest suite-controls-the-default-selection
  (is (= [:register :set]
         (:workloads (runner/parse-args sample-suite []))))
  (is (= [:register :set]
         (:workloads
          (runner/parse-args sample-suite ["--all-workloads"])))))

(deftest invalid-input-is-rejected-before-a-run
  (doseq [args [["--workload" "unknown"]
                ["--profile" "unknown"]
                ["--fault" "typo"]
                ["--time-limit" "0"]
                ["--sync" "sometimes"]
                ["--unknown"]
                ["--workload"]
                ["--all-workloads" "--workload" "set"]]]
    (testing (pr-str args)
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (runner/parse-args sample-suite args)))]
        (is (= :invalid-suite-runner-input
               (:lite/error (ex-data e))))))))

(deftest validates-the-suite-shape
  (doseq [suite [(dissoc sample-suite :name)
                 (assoc sample-suite :workloads [])
                 (assoc sample-suite :profiles {})
                 (assoc sample-suite :default-profile :missing)
                 (assoc sample-suite :default-workloads [])
                 (assoc sample-suite :default-workloads [:missing])
                 (assoc-in sample-suite [:profiles :embedded :build] :not-a-fn)
                 (assoc-in sample-suite [:options :sync :parse] :not-a-fn)
                 (assoc sample-suite :options
                        {:workload {:long "--workload"}})]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (runner/validate-suite! suite)))))

(deftest runs-every-selected-workload-through-the-selected-profile
  (let [configs (atom [])
        outcome (atom nil)
        output  (with-out-str
                  (with-redefs [core/run
                                (fn [config]
                                  (swap! configs conj config)
                                  {:valid? (not= :set (:workload config))})]
                    (reset! outcome
                            (runner/run-suite
                             sample-suite
                             ["--profile" "process"
                              "--fault" "crash"
                              "--time-limit" "10"]))))]
    (is (= [{:profile :process
             :workload :register
             :opts {:sync? true
                    :nemesis [:crash]
                    :time-limit 10}}
            {:profile :process
             :workload :set
             :opts {:sync? true
                    :nemesis [:crash]
                    :time-limit 10}}]
           @configs))
    (is (= 1 (:exit-code @outcome)))
    (is (= [true false] (mapv :valid? (:runs @outcome))))
    (is (re-find #"sample summary" output))
    (is (re-find #"process\s+set\s+crash\s+:valid\? false" output))))

(deftest help-and-list-do-not-run-a-test
  (doseq [[arg action text] [["--help" :help "durability mode"]
                             ["--list" :list "Workloads: register, set"]]]
    (let [called? (atom false)
          outcome (atom nil)
          output  (with-out-str
                    (with-redefs [core/run (fn [_] (reset! called? true))]
                      (reset! outcome (runner/run-suite sample-suite [arg]))))]
      (is (false? @called?))
      (is (= action (:action @outcome)))
      (is (= 0 (:exit-code @outcome)))
      (is (re-find (re-pattern text) output)))))

(deftest resolves-a-qualified-suite-var
  (is (= sample-suite
         (runner/resolve-suite "lite.runner-test/sample-suite")))
  (is (thrown? clojure.lang.ExceptionInfo
               (runner/resolve-suite "sample-suite"))))

;; ## A fault that was asked for has to actually happen

(def ^:private forgetful-suite
  "A suite whose profile drops the options it was handed -- the mistake that
   makes `--fault` do nothing while the run still reports a verdict."
  {:name              "forgetful"
   :workloads         [:register]
   :default-workloads :all
   :default-profile   :embedded
   :profiles          {:embedded {:build (fn [workload _opts]
                                           {:workload workload})}}})

(deftest a-fault-that-never-happened-is-not-a-passing-run
  (let [outcome (atom nil)
        output  (with-out-str
                  (with-redefs [core/run (fn [_]
                                           ;; A real run, with a real history,
                                           ;; in which no fault was injected --
                                           ;; because :nemesis never arrived.
                                           {:valid? true, :history []})]
                    (reset! outcome (runner/run-suite forgetful-suite
                                                      ["--fault" "crash"]))))]
    (is (= 1 (:exit-code @outcome)) "a run that tested nothing cannot pass")
    (is (= [:error] (mapv :valid? (:runs @outcome))))
    (testing "and says which mistake it was"
      (is (re-find #"no fault was injected" output))
      (is (re-find #"passes its `opts` through" output))))

  (testing "a run that did inject the fault is left alone"
    (with-redefs [core/run (fn [_]
                             {:valid? true
                              :history [{:process :nemesis, :f :crash
                                         :type :info, :value 1}]})]
      (is (= 0 (:exit-code (runner/run-suite forgetful-suite
                                             ["--fault" "crash"]))))))

  (testing "every requested fault must have completed"
    (with-redefs [core/run (fn [_]
                             {:valid? true
                              :history [{:process :nemesis, :f :crash
                                         :type :info, :value 1}]})]
      (let [outcome (runner/run-suite forgetful-suite
                                      ["--fault" "crash"
                                       "--fault" "pause"])]
        (is (= 1 (:exit-code outcome)))
        (is (= [:pause] (:missing-faults
                         (ex-data (:thrown (first (:runs outcome))))))))))

  (testing "resume and invocation ops do not prove the requested fault happened"
    (doseq [history [[{:process :nemesis, :f :resume, :type :info}]
                     [{:process :nemesis, :f :pause, :type :invoke}]]]
      (with-redefs [core/run (fn [_] {:valid? true, :history history})]
        (is (= 1 (:exit-code (runner/run-suite forgetful-suite
                                               ["--fault" "pause"])))))))

  (testing "and a run with no faults asked for is nobody's business"
    (with-redefs [core/run (fn [_] {:valid? true, :history []})]
      (is (= 0 (:exit-code (runner/run-suite forgetful-suite [])))))))

;; ## Faults a profile cannot take are refused before anything runs

(def ^:private typed-suite
  (assoc-in sample-suite [:profiles :embedded :target-type] :in-process))

(deftest a-fault-the-profile-cannot-inject-is-refused-up-front
  (let [called? (atom false)]
    (with-redefs [core/run (fn [_] (reset! called? true) {:valid? true})]
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (runner/run-suite typed-suite
                                             ["--profile" "embedded"
                                              "--fault" "pause"])))]
        (is (= :invalid-nemesis (:lite/error (ex-data e))))
        (is (re-find #"why:" (ex-message e)))))
    (is (false? @called?) "nothing ran, so nothing had to be undone"))

  (testing "a fault it can inject is allowed through"
    (with-redefs [core/run (fn [_]
                             {:valid? true
                              :history [{:process :nemesis, :f :crash
                                         :type :info, :value 1}]})]
      (is (nil? (:error (first (:runs (runner/run-suite
                                       typed-suite
                                       ["--profile" "embedded"
                                        "--fault" "crash"
                                        "--workload" "register"]))))))))

  (testing "and help says which faults each typed profile can take"
    (is (re-find #"embedded\s+faults: crash" (runner/help typed-suite)))))

;; ## One workload failing doesn't discard the rest

(deftest a-failing-workload-does-not-lose-the-others
  (let [outcome (atom nil)
        output  (with-out-str
                  (with-redefs [core/run
                                (fn [{:keys [workload]}]
                                  (if (= :set workload)
                                    (throw (ex-info "the target would not start"
                                                    {:lite/error :target-start-failed}))
                                    {:valid? true, :history []}))]
                    (reset! outcome (runner/run-suite sample-suite
                                                      ["--all-workloads"]))))]
    (is (= [true :error] (mapv :valid? (:runs @outcome)))
        "the workload that passed is still reported")
    (is (= 1 (:exit-code @outcome)))
    (is (re-find #"set did not finish" output))
    (is (re-find #"the target would not start" output))))

(deftest a-fatal-error-is-not-converted-to-a-workload-result
  (with-redefs [core/run (fn [_] (throw (AssertionError. "broken invariant")))]
    (is (thrown-with-msg? AssertionError
                          #"broken invariant"
                          (runner/run-suite sample-suite
                                            ["--workload" "register"])))))

;; ## Suite options

(deftest an-option-cannot-claim-a-key-the-runner-fills-in
  (doseq [spec [{:key :nemesis} {:key :time-limit} {:key :concurrency}]]
    (testing (:key spec)
      (let [suite (assoc sample-suite :options {:mine spec})
            e     (is (thrown? clojure.lang.ExceptionInfo
                               (runner/validate-suite! suite)))]
        (is (re-find #"already fills in" (ex-message e)))))))

(deftest a-reserved-key-error-names-the-real-common-option
  (let [suite (assoc sample-suite :options {:mine {:key :nemesis}})
        e     (is (thrown? clojure.lang.ExceptionInfo
                           (runner/validate-suite! suite)))]
    (is (re-find #"--fault" (ex-message e)))
    (is (not (re-find #"--nemesis" (ex-message e))))))

(deftest a-default-keeps-the-type-the-suite-gave-it
  (let [suite (assoc sample-suite
                     :options {:flag {:default true}
                               :size {:default 5}
                               :mode {:default "on"
                                      :values ["on" "off"]
                                      :parse #(= "on" %)}})]
    (is (= {:flag true, :size 5, :mode true}
           (:options (runner/parse-args suite []))))))

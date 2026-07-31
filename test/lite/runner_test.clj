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

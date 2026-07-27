(ns lite.compose-docker-test
  "The `:compose` target-type against a real Docker daemon: containers really
   brought up, really killed, really cut off the network.

   Opt-in, because it is slow and needs a daemon:

     JEPSEN_LITE_DOCKER=1 clojure -M:test -n lite.compose-docker-test

   Without that it skips itself and says so. A suite that silently needed
   Docker would fail for the wrong reason on any machine without it, and a
   suite that quietly did nothing would be worse still."
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [lite.core :as core]
            [lite.http-targets :as http-targets]
            [lite.target.compose :as compose]))

(def ^:private compose-file "examples/compose/docker-compose.yml")
(def ^:private url "http://127.0.0.1:8080")

(defn- docker-available?
  []
  (and (System/getenv "JEPSEN_LITE_DOCKER")
       (try (zero? (:exit (shell/sh "docker" "info")))
            (catch Exception _ false))))

(defn- config
  [workload opts]
  (merge {:adapter  (http-targets/map->HttpAdapter {:url url})
          :handler  (get http-targets/handlers workload)
          :workload workload
          :name     (str "jepsen-lite-test-compose-" (name workload))
          :target   {:type      :compose
                     :file      compose-file
                     :service   "kvs"
                     :container "jepsen-lite-kvs"
                     :url       url
                     ;; The first run builds the image.
                     :ready-timeout  300000
                     :fault-duration "5s"}}
         opts))

(defn- skip [what]
  (println (str "SKIPPING " what ": set JEPSEN_LITE_DOCKER=1 and start Docker"
                " to run it."))
  true)

(deftest a-containerized-target-runs-the-workloads
  (if-not (docker-available?)
    (is (skip "compose: workloads"))
    (doseq [workload [:counter :set :bank :register]]
      (testing (name workload)
        (is (true? (:valid? (core/run (config workload {})))))))))

(deftest a-container-can-be-killed-and-comes-back-with-its-data
  (if-not (docker-available?)
    (is (skip "compose: crash"))
    (let [{:keys [valid? history]}
          (core/run (config :counter {:nemesis      [:crash]
                                      :time-limit   60
                                      :nemesis-opts {:faults 2
                                                     :fault-interval 5}}))
          crashes (filter (fn [op] (and (= :crash (:f op))
                                        (= :info (:type op))
                                        (:value op)))
                          history)]
      (is (seq crashes) "the container really was killed")
      (testing "and the store kept what it acknowledged, on its volume"
        (is (true? valid?))))))

(deftest a-container-can-be-cut-off-the-network
  ;; The fault no other target-type has. A partitioned container is still
  ;; running and still receiving requests -- nothing it says gets back -- so
  ;; the ops caught by it are indeterminate rather than failed.
  (if-not (docker-available?)
    (is (skip "compose: partition"))
    (let [{:keys [valid? history]}
          (core/run (config :counter {:adapter (http-targets/map->HttpAdapter
                                                {:url url
                                                 :request-timeout
                                                 (java.time.Duration/ofSeconds 2)})
                                      :nemesis      [:partition]
                                      :time-limit   60
                                      :nemesis-opts {:faults 2
                                                     :fault-interval 5}}))]
      (is (seq (filter (comp #{:partition} :f) history)))
      (testing "ops that met the partition are indeterminate"
        (is (some (fn [op] (and (number? (:process op)) (= :info (:type op))))
                  history)))
      (testing "and the target is still correct"
        (is (true? valid?))))))

(deftest the-target-is-torn-down-afterwards
  (if-not (docker-available?)
    (is (skip "compose: teardown"))
    (do (core/run (config :counter {:workload-opts {:op-limit 20}}))
        (testing "the container is gone once the run is over"
          (let [{:keys [out]} (shell/sh "docker" "ps" "--filter"
                                        "name=jepsen-lite-kvs" "--format" "{{.Names}}")]
            (is (not (re-find #"jepsen-lite-kvs" out))))))))

(deftest command-lines-are-the-ones-docker-actually-accepts
  ;; Cheap even without a daemon: `docker compose config` parses the file and
  ;; the flags without running anything.
  (if-not (docker-available?)
    (is (skip "compose: command lines"))
    (let [{:keys [exit]} (apply shell/sh (compose/compose-command
                                          {:file compose-file} "config"))]
      (is (zero? exit) "the compose file parses"))))

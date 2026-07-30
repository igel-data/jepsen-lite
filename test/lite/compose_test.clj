(ns lite.compose-test
  "M6 acceptance, part B: the containerized target-type, and the only one that
   can be partitioned.

   What a `:compose` target-type *is*, in the end, is a set of command lines --
   `docker compose up`, `pumba kill`, `pumba netem` -- so that is what these
   tests check, along with the validation around them. Bringing containers up
   and killing them for real needs a Docker daemon, which a unit test suite has
   no business requiring; `lite.compose-docker-test` covers that, and skips
   itself when there is no daemon to talk to."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            ;; Loaded for its side effect: `lite.core` is what pulls in every
            ;; target-type, so `methods` below sees all of them.
            [lite.core]
            [lite.nemesis :as nemesis]
            [lite.target :as target]
            [lite.target.compose :as compose]))

(def ^:private config
  {:type      :compose
   :file      "examples/compose/docker-compose.yml"
   :service   "kvs"
   :container "jepsen-lite-kvs"
   :url       "http://127.0.0.1:8080"})

(deftest compose-is-runnable-now
  (is (contains? (methods target/build) :compose))
  (testing "so every target-type in the design is, and v1's scope is complete"
    (is (= (set target/target-types)
           (disj (set (keys (methods target/build))) :default)))))

;; ## The command lines

(deftest the-target-is-brought-up-and-taken-down-by-file
  (is (= ["docker" "compose" "-f" "examples/compose/docker-compose.yml"
          "up" "-d" "--wait" "kvs"]
         (compose/up-command config)))

  (testing "--wait, because a published port answers before the service does"
    ;; Found the hard way: Docker binds the host side of a port mapping as soon
    ;; as the container is created, so a TCP probe says 'ready' while the
    ;; process inside is still starting, and the first ops of the run fail for
    ;; a reason that has nothing to do with the target.
    (is (some #{"--wait"} (compose/up-command config))))

  (testing "and down takes its volumes with it, so the next run starts empty"
    (is (= ["docker" "compose" "-f" "examples/compose/docker-compose.yml"
            "down" "-v"]
           (compose/down-command config)))))

(deftest faults-are-pumba-commands-aimed-at-the-container
  (let [config (assoc config :fault-duration "5s")]
    (testing "crash is a real SIGKILL"
      (let [command (compose/kill-command config)]
        (is (= ["kill" "--signal" "SIGKILL" "jepsen-lite-kvs"]
               (take-last 4 command)))))

    (testing "pause heals itself when its duration is up"
      (is (= ["pause" "--duration" "5s" "jepsen-lite-kvs"]
             (take-last 4 (compose/pause-command config)))))

    (testing "partition is total egress loss, for a duration"
      (let [command (compose/partition-command config)]
        (is (= ["loss" "--percent" "100" "jepsen-lite-kvs"]
               (take-last 4 command)))
        (is (some #{"--duration"} command))
        (testing "with an image that has tc in it"
          ;; netem needs tc, which a target's own image is unlikely to carry.
          ;; Without --tc-image the fault silently does nothing, which is worse
          ;; than failing: the run would look like a passed partition test.
          (is (some #{"--tc-image"} command))
          (is (some #(str/includes? % "nettools") command)))))

    (testing "and Pumba is a container itself, so nothing has to be installed"
      (doseq [command [(compose/kill-command config)
                       (compose/pause-command config)
                       (compose/partition-command config)]]
        (is (= ["docker" "run" "--rm"] (take 3 command)))
        (is (some #{"/var/run/docker.sock:/var/run/docker.sock"} command))
        (is (some #(str/includes? % "pumba") command))))))

;; ## Axis 2: the row that completes the table

(def ^:private container-faults
  "Everything a container can be done to. `:power-off` is the exception, and
   deliberately so: it needs a FUSE mount under the target's data directory,
   which needs capabilities Lite doesn't ask a container for yet."
  [:crash :pause :partition])

(deftest compose-accepts-every-fault-that-happens-to-a-container
  (doseq [intent container-faults]
    (testing intent
      (is (true? (get-in nemesis/validity [:compose intent])))
      (is (= [intent] (nemesis/validate! :compose [intent])))))

  (testing "including all of them at once"
    (is (= container-faults (nemesis/validate! :compose container-faults))))

  (testing "but not power-off, which is deferred rather than impossible"
    (is (false? (get-in nemesis/validity [:compose :power-off])))
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (nemesis/validate! :compose [:power-off])))]
      (is (= :invalid-nemesis (:lite/error (ex-data e))))
      (is (str/includes? (ex-message e) "FUSE"))
      (is (str/includes? (ex-message e) ":local-process")
          "and says where a power-off can be done instead"))))

(deftest partition-is-possible-here-and-nowhere-else
  ;; The point of the axis, in one assertion: a fault is available where Lite
  ;; owns the thing the fault happens to.
  (is (= [:compose]
         (->> nemesis/validity
              (keep (fn [[target-type row]]
                      (when (:partition row) target-type)))
              vec))))

;; ## Configuration

(deftest a-compose-target-lite-cannot-aim-at-is-refused
  (testing "no compose file"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (target/build {:type :compose} nil)))]
      (is (= :invalid-target (:lite/error (ex-data e))))
      (is (str/includes? (ex-message e) ":file"))))

  (testing "no container for Pumba to hit"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (target/build (dissoc config :container) nil)))]
      (is (= :invalid-target (:lite/error (ex-data e))))
      (is (str/includes? (ex-message e) "container_name"))))

  (testing "a url that isn't one"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (target/build (assoc config :url "8080") nil)))]
      (is (= :invalid-target (:lite/error (ex-data e)))))))

(deftest a-compose-target-is-built-with-its-defaults
  (let [target (target/build config nil)]
    (is (= 0 (compose/crash-count target)))
    (testing "including the tc image netem needs and a fault duration"
      (is (str/includes? (:tc-image (:config target)) "nettools"))
      (is (string? (:fault-duration (:config target)))))))

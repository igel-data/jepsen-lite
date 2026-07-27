(ns lite.local-process-test
  "M6 acceptance, part A: a target Lite runs as a separate OS process, and the
   first faults the operating system carries out for it.

   The target here is the *same* HTTP KVS the `:http` tests use, driven by the
   *same* adapter and the *same* handlers -- started as a program of its own,
   with a data directory. Nothing on the client side knows the difference, which
   is the point: `:local-process` differs from `:http` only in who owns the
   target's lifetime, and that is exactly the axis it belongs to.

   What the crash tests prove is narrower than 'IgelDB is durable', and worth
   being precise about: SIGKILL kills the process, not the kernel, so writes the
   store handed to the OS survive it. What dies with the process is whatever the
   store still had in its own memory. That is a real bug class -- and the
   `:buffered` driver here has exactly that bug, so we can watch a checker catch
   it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lite.client :as client]
            [lite.core :as core]
            [lite.http-targets :as http-targets]
            [lite.nemesis :as nemesis]
            [lite.target :as target]
            [lite.target.local-process :as local]
            [lite.workload :as workload])
  (:import (java.io File)
           (java.net ServerSocket)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Duration)))

;; ## Starting the driver
;;
;; The command Lite runs is an ordinary one: a JVM, this classpath, the KVS
;; namespace's -main. Lite neither knows nor cares that the program it is about
;; to kill happens to be written in the same language.

(defn- free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- temp-dir []
  (str (Files/createTempDirectory "jepsen-lite-target"
                                  (into-array FileAttribute []))))

(defn- rm-rf [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf (.listFiles f)))
    (.delete f)))

(defn- driver-command
  [port data-dir {:keys [variant durability] :or {variant :correct
                                                  durability :durable}}]
  [(str (System/getProperty "java.home") File/separator "bin"
        File/separator "java")
   "-cp" (System/getProperty "java.class.path")
   "clojure.main" "-m" "lite.http-targets"
   "--port"       (str port)
   "--data-dir"   data-dir
   "--variant"    (name variant)
   "--durability" (name durability)])

(defn- config
  "A run config: the M5 HTTP adapter and handler, against a process Lite runs.

   Read this next to `lite.http-targets/config` -- the `:target` is the only
   thing that differs, and the url appears twice because the two axes each need
   it: the adapter has to call the target, and the target-type has to know when
   it has come up."
  ([workload] (config workload {}))
  ([workload {:keys [data-dir port request-timeout nemesis nemesis-opts
                     time-limit workload-opts]
              :as   opts}]
   (let [port     (or port (free-port))
         data-dir (or data-dir (temp-dir))
         url      (str "http://127.0.0.1:" port)]
     (cond-> {:adapter  (http-targets/map->HttpAdapter
                         {:url url, :request-timeout request-timeout})
              :handler  (get http-targets/handlers workload)
              :workload workload
              :name     (str "jepsen-lite-test-local-" (name workload))
              :target   {:type    :local-process
                         :command (driver-command port data-dir opts)
                         :url     url
                         :log     (str data-dir File/separator "target.log")}}
       workload-opts (assoc :workload-opts workload-opts)
       time-limit    (assoc :time-limit time-limit)
       nemesis       (assoc :nemesis nemesis)
       nemesis-opts  (assoc :nemesis-opts nemesis-opts)))))

(defn- with-data-dir
  "Runs `f` with a fresh data directory, and clears it up afterwards."
  [f]
  (let [dir (temp-dir)]
    (try (f dir) (finally (rm-rf dir)))))

;; ## The goal: Lite runs the target, and the workloads don't notice

(deftest every-workload-runs-against-a-process-lite-started
  (doseq [workload (keys workload/workloads)]
    (testing (name workload)
      (with-data-dir
        (fn [dir]
          (is (true? (:valid? (core/run (config workload {:data-dir dir}))))))))))

(deftest the-target-is-started-and-stopped-around-the-run
  ;; Lite owns this process. Leaving one running after a run would be the kind
  ;; of bug you only find later, in `ps`.
  (with-data-dir
    (fn [dir]
      (let [port    (free-port)
            url     (str "http://127.0.0.1:" port)
            adapter (http-targets/map->HttpAdapter {:url url})
            conn    (target/build
                     {:type    :local-process
                      :command (driver-command port dir {})
                      :url     url
                      :log     (str dir "/target.log")}
                     adapter)]
        (is (false? (local/alive? conn)) "not started yet")
        (target/start! conn)
        (try
          (is (true? (local/alive? conn)))
          (is (some? (local/pid conn)))

          (testing "and it is serving, so ops work"
            (target/acquire! conn)
            (let [invoke (fn [op] (client/invoke
                                   (assoc adapter :handler
                                          (:register http-targets/handlers))
                                   (target/current conn) op))]
              (is (= :ok (:type (invoke {:type :invoke, :f :write
                                         :key 0, :value 42}))))
              (is (= 42 (:value (invoke {:type :invoke, :f :read, :key 0}))))))
          (finally
            (target/stop! conn)))
        (is (false? (local/alive? conn)) "stopped afterwards")
        (is (nil? (local/pid conn)))))))

;; ## crash: a real kill -9

(deftest a-crash-is-a-real-kill-and-a-real-restart
  (with-data-dir
    (fn [dir]
      (let [port    (free-port)
            url     (str "http://127.0.0.1:" port)
            adapter (assoc (http-targets/map->HttpAdapter {:url url})
                           :handler (:register http-targets/handlers))
            conn    (target/build
                     {:type    :local-process
                      :command (driver-command port dir {})
                      :url     url
                      :log     (str dir "/target.log")}
                     adapter)
            invoke  (fn [op] (client/invoke adapter (target/current conn) op))]
        (target/start! conn)
        (try
          (target/acquire! conn)
          (let [before (local/pid conn)]
            (is (= :ok (:type (invoke {:type :invoke, :f :write
                                       :key 0, :value 42}))))

            (local/crash! conn)

            (testing "a different process is serving now"
              (is (some? (local/pid conn)))
              (is (not= before (local/pid conn)))
              (is (= 1 (local/crash-count conn))))

            (testing "and it recovered the write from disk"
              ;; The heart of it: `open` re-attaches to what is on disk, it
              ;; does not create or reset it. A store that came back empty
              ;; would be passing a crash test by forgetting the question.
              (is (= 42 (:value (invoke {:type :invoke, :f :read, :key 0})))))

            (testing "and it keeps surviving, crash after crash"
              (dotimes [_ 2] (local/crash! conn))
              (is (= 3 (local/crash-count conn)))
              (is (= 42 (:value (invoke {:type :invoke, :f :read, :key 0}))))))
          (finally
            (target/stop! conn)))))))

;; A counter, not a set, for the crash runs: its state is one key, so the
;; target's log stays small however long the run goes on -- and a run has to
;; last several seconds for a process restart to fit inside it. The checker
;; still answers the durability question, by catching reads that fall below the
;; increments the target acknowledged.

;; The interval has to leave room for ops to *complete* between kills, not just
;; to be issued. A restarting JVM takes a second or so on a laptop and rather
;; longer on a shared CI runner, and ops issued into that window come back
;; `:info` -- indeterminate, and so not writes the target ever promised to
;; keep. Faults arriving faster than the target recovers produce a run in which
;; nothing was acknowledged, which no durability checker can say anything
;; about. The interval also delays the first fault (see `lite.nemesis`), so
;; there is something acknowledged before the first kill lands.

(def ^:private crash-opts
  {:nemesis      [:crash]
   :time-limit   20
   :nemesis-opts {:faults 3, :fault-interval 4}})

(def durable-run
  (delay (with-data-dir
           (fn [dir] (core/run (config :counter (assoc crash-opts
                                                       :data-dir dir)))))))

(defn- crashes [history]
  (filter (fn [op] (and (= :crash (:f op)) (= :info (:type op)) (:value op)))
          history))

(deftest crashes-happen-during-the-run-and-clients-carry-on
  (let [{:keys [history]} @durable-run
        cs (crashes history)]
    (is (< 1 (count cs)) "one run, several kills")
    (is (= (range 1 (inc (count cs))) (map :value cs)))

    (testing "as many as were asked for"
      ;; `gen/limit` counts a `gen/sleep` as an op, so a limit taken over the
      ;; faults alone delivers half of them. Compose had exactly that bug, and
      ;; only a run against real Docker showed it up.
      (is (= (:faults (:nemesis-opts crash-opts)) (count cs))))

    (testing "ops land in the down window and are recorded honestly"
      ;; Never :ok. A refused connection is a certain :fail -- the request
      ;; never reached the target -- and a timeout is an honest :info.
      (let [down (filter (fn [op] (and (number? (:process op))
                                       (contains? #{:fail :info} (:type op))))
                         history)]
        (is (seq down) "a kill -9 mid-run should cost some ops")))

    (testing "and clients keep working afterwards"
      ;; Anchored on the first kill, not the last: the last one can land in the
      ;; final moments of the run, and "no ops after it" would say nothing
      ;; about whether clients recovered.
      (let [after (filter (fn [op] (< (:time (first cs)) (:time op))) history)]
        (is (some (fn [op] (and (= :ok (:type op)) (number? (:process op))))
                  after))))))

(deftest a-store-that-fsyncs-survives-being-killed
  (is (true? (:valid? @durable-run))))

(deftest a-buffered-write-does-not-survive-the-kill-that-follows-it
  ;; The mechanism, without a nemesis schedule to race: acknowledge a write,
  ;; kill -9, read. This is the assertion the end-to-end run below depends on,
  ;; and the only one of the two that can't be defeated by a slow machine.
  (with-data-dir
    (fn [dir]
      (let [port    (free-port)
            url     (str "http://127.0.0.1:" port)
            adapter (assoc (http-targets/map->HttpAdapter {:url url})
                           :handler (:register http-targets/handlers))
            conn    (target/build
                     {:type    :local-process
                      :command (driver-command port dir {:durability :buffered})
                      :url     url
                      :log     (str dir "/target.log")}
                     adapter)
            invoke  (fn [op] (client/invoke adapter (target/current conn) op))]
        (target/start! conn)
        (try
          (target/acquire! conn)
          (is (= :ok (:type (invoke {:type :invoke, :f :write
                                     :key 0, :value 42})))
              "the store acknowledged the write")

          (local/crash! conn)

          (testing "and then lost it, because it never left the store's memory"
            (is (nil? (:value (invoke {:type :invoke, :f :read, :key 0})))))
          (finally
            (target/stop! conn)))))))

(deftest a-store-that-buffers-acknowledged-writes-does-not
  ;; The bug SIGKILL can catch: acknowledged before it left the store's own
  ;; memory. The process dies with the buffer, and the increments the target
  ;; promised to keep are gone.
  (with-data-dir
    (fn [dir]
      (let [{:keys [valid? results history]}
            (core/run (config :counter (assoc crash-opts
                                              :data-dir dir
                                              :durability :buffered)))]
        (testing "the run had something to lose in the first place"
          ;; Check the premise before the conclusion. A run in which nothing
          ;; was acknowledged before a kill cannot detect a store that loses
          ;; acknowledged writes, and would otherwise report that as the store
          ;; behaving -- which is how this test passed in CI while proving
          ;; nothing. If this fails, the fault schedule is outrunning the
          ;; target's recovery: raise :fault-interval.
          (let [first-kill (:time (first (crashes history)))
                acked      (filter (fn [op] (and (= :ok (:type op))
                                                 (= :add (:f op))
                                                 (< (:time op) first-kill)))
                                   history)]
            (is (some? first-kill) "the target was killed")
            (is (seq acked)
                "some add was acknowledged before the first kill")))

        (is (false? valid?))
        (testing "as reads below what the target acknowledged"
          (let [[lower value _upper] (first (:errors results))]
            (is (seq (:errors results)))
            (is (< value lower))))))))

;; ## pause: SIGSTOP and SIGCONT

(deftest a-pause-stops-the-target-answering-without-killing-it
  (with-data-dir
    (fn [dir]
      (let [port    (free-port)
            url     (str "http://127.0.0.1:" port)
            adapter (assoc (http-targets/map->HttpAdapter
                            {:url url, :request-timeout (Duration/ofMillis 300)})
                           :handler (:register http-targets/handlers))
            conn    (target/build
                     {:type    :local-process
                      :command (driver-command port dir {})
                      :url     url
                      :log     (str dir "/target.log")}
                     adapter)
            invoke  (fn [op] (client/invoke adapter (target/current conn) op))]
        (target/start! conn)
        (try
          (target/acquire! conn)
          (let [pid (local/pid conn)]
            (is (= :ok (:type (invoke {:type :invoke, :f :write
                                       :key 0, :value 42}))))

            (local/pause! conn)
            (testing "the process is still there, and still isn't answering"
              (is (true? (local/alive? conn)))
              (is (= pid (local/pid conn)) "paused, not replaced")
              ;; Indeterminate, not failed: a paused target may well apply the
              ;; request the moment it wakes up.
              (is (= :info (:type (invoke {:type :invoke, :f :write
                                           :key 1, :value 7})))))

            (local/resume! conn)
            (testing "and it picks up where it left off"
              (is (= :ok (:type (invoke {:type :invoke, :f :read, :key 0}))))
              (is (= 42 (:value (invoke {:type :invoke, :f :read, :key 0}))))))
          (finally
            (target/stop! conn)))))))

(deftest a-run-with-pauses-still-checks-out
  (with-data-dir
    (fn [dir]
      (let [{:keys [valid? history]}
            (core/run (config :counter
                              {:data-dir        dir
                               :request-timeout (Duration/ofMillis 500)
                               :nemesis         [:pause]
                               :time-limit      10
                               ;; A pause lasts until its resume, and the two
                               ;; alternate at the fault interval -- so the
                               ;; interval is how long the target stays stopped,
                               ;; and it wants to be longer than the client's
                               ;; request timeout above or a pause would only
                               ;; slow ops down rather than leave them unknown.
                               :nemesis-opts    {:faults 4, :fault-interval 2}}))
            paused (filter (comp #{:pause} :f) history)]
        (is (seq paused))
        (is (seq (filter (comp #{:resume} :f) history)))

        (testing "ops caught by the pause are indeterminate, not failures"
          (is (some (fn [op] (and (number? (:process op))
                                  (= :info (:type op))))
                    history)))

        (testing "and the target is still correct"
          (is (true? valid?)))))))

;; ## Axis 2

(deftest local-process-accepts-crash-and-pause
  (is (= [:crash] (nemesis/validate! :local-process [:crash])))
  (is (= [:pause] (nemesis/validate! :local-process [:pause])))
  (is (= [:crash :pause] (nemesis/validate! :local-process [:crash :pause]))))

(deftest local-process-refuses-partition
  (let [e   (is (thrown? clojure.lang.ExceptionInfo
                         (nemesis/validate! :local-process [:partition])))
        msg (ex-message e)]
    (is (= :invalid-nemesis (:lite/error (ex-data e))))
    (is (str/includes? msg ":partition"))
    (is (str/includes? msg "why:"))
    (is (str/includes? msg "no network to partition"))
    (is (str/includes? msg "fix:"))
    ;; the faults it can do instead, and where partition is possible
    (is (str/includes? msg "crash"))
    (is (str/includes? msg ":compose"))))

(deftest a-command-lite-cannot-run-is-refused
  (testing "no :command at all"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (target/build {:type :local-process} nil)))]
      (is (= :invalid-target (:lite/error (ex-data e))))
      (is (str/includes? (ex-message e) ":command"))))

  (testing "a program that isn't there"
    (let [conn (target/build {:type    :local-process
                              :command ["definitely-not-a-program"]
                              :url     "http://127.0.0.1:1"}
                             nil)]
      (is (thrown? java.io.IOException (target/start! conn)))))

  (testing "a program that starts but never listens"
    (with-data-dir
      (fn [dir]
        (let [log  (str dir "/target.log")
              conn (target/build {:type    :local-process
                                  ;; Exits at once, so it never listens.
                                  :command [(str (System/getProperty "java.home")
                                                 "/bin/java") "-version"]
                                  :url     (str "http://127.0.0.1:" (free-port))
                                  :log     log
                                  :ready-timeout 3000}
                                 nil)
              e    (is (thrown? clojure.lang.ExceptionInfo
                                (target/start! conn)))]
          (is (= :target-start-failed (:lite/error (ex-data e))))
          (testing "and says what the target said before it gave up"
            (is (str/includes? (ex-message e) "it said:"))))))))

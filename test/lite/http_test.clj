(ns lite.http-test
  "M5 acceptance: the four workloads run against a target Lite doesn't own,
   reached over HTTP, and the checkers discriminate there exactly as they do
   in-process.

   What these tests are really about is what *isn't* here. There is no HTTP
   workload, no HTTP checker, no HTTP branch in the bridge or the wrapper, and
   no second error-classification path. `lite.http-targets` writes a handler
   that speaks HTTP and Lite gains a target-type that opens connections; the
   rest of the library never learns that either happened."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lite.client :as client]
            [lite.core :as core]
            [lite.http-targets :as http-targets]
            [lite.nemesis :as nemesis]
            [lite.target :as target]
            [lite.target.http :as http]
            [lite.workload :as workload])
  (:import (java.net InetAddress ServerSocket)
           (java.time Duration)))

(defn- with-server
  "Runs `f` against a freshly started HTTP KVS, and stops it afterwards. The
   fixture starts the target; the `:http` target-type never does."
  [variant f]
  (let [{:keys [stop] :as server} (http-targets/server {:variant variant})]
    (try (f server) (finally (stop)))))

(defn- run
  "One workload against a store of this variant, over HTTP."
  [workload variant]
  (with-server variant
    (fn [{:keys [url]}]
      (core/run (http-targets/config workload url)))))

;; ## The goal: every workload, both directions, over HTTP

(deftest every-workload-runs-over-http-in-both-directions
  (doseq [workload (keys workload/workloads)]
    (testing (str (name workload) ", correct server")
      (is (true? (:valid? (run workload :correct)))))

    (testing (str (name workload) ", broken server")
      (is (false? (:valid? (run workload :broken)))))))

(deftest broken-servers-fail-for-the-right-reason
  ;; The same anomalies the in-process fixtures produce, found by the same
  ;; checkers. Only the wire between the workload and the defect changed.
  (testing "register: a CAS no linearization allows"
    (let [{:keys [results]} (run :register :broken)]
      (is (seq (:failures results)))
      (is (every? (comp seq :final-paths)
                  (->> (:results results) vals (remove :valid?)
                       (map :linearizable))))))

  (testing "set: acknowledged appends missing from the final read"
    (let [{:keys [results]} (run :set :broken)]
      (is (pos? (:lost-count results)))
      (is (zero? (:unexpected-count results)))))

  (testing "bank: money that stops adding up"
    (let [{:keys [results]} (run :bank :broken)]
      (is (contains? (:errors results) :wrong-total))))

  (testing "counter: reads below the acknowledged sum"
    (let [{:keys [results]} (run :counter :broken)
          [lower value _upper] (first (:errors results))]
      (is (seq (:errors results)))
      (is (< value lower)))))

(deftest bank-seeds-its-accounts-over-http
  ;; M4.5's init phase, unchanged: the workload opens its accounts with an
  ;; ordinary op that now travels over HTTP, before any transfer. Nothing in
  ;; the workload knows the difference.
  (with-server :correct
    (fn [{:keys [url store]}]
      (let [{:keys [valid? history]} (core/run (http-targets/config :bank url))
            inits     (filter (comp #{:init} :f) history)
            first-txn (first (filter (comp #{:transfer} :f) history))]
        (is (seq inits))
        (is (every? (comp #{:invoke :ok} :type) inits)
            "seeding the accounts is not allowed to fail")
        (is (< (:index (last inits)) (:index first-txn))
            "and it finishes before the first transfer")

        (testing "the accounts really landed in the server's store"
          (is (seq @store))
          (is (every? number? (vals @store))))

        (is (true? valid?))))))

;; ## Axis 2: an :http target can't be perturbed at all

(deftest http-is-runnable-now
  (is (contains? (methods target/build) :http)
      ":http has a lifecycle, so it is selectable"))

(deftest http-refuses-every-nemesis
  (doseq [intent nemesis/intents]
    (testing intent
      (is (false? (get-in nemesis/validity [:http intent])))
      (let [e   (is (thrown? clojure.lang.ExceptionInfo
                             (core/validate!
                              {:target  {:type :http, :url "http://127.0.0.1:1"}
                               :nemesis [intent]})))
            msg (ex-message e)]
        (is (= :invalid-nemesis (:lite/error (ex-data e))))
        (testing "says what, why and how to fix it"
          (is (str/includes? msg (str intent)))
          (is (str/includes? msg ":http"))
          (is (str/includes? msg "why:"))
          (is (str/includes? msg "doesn't run"))
          (is (str/includes? msg "fix:"))
          (is (str/includes? msg "without a nemesis"))
          (testing "and names somewhere the fault the user asked for is possible"
            ;; Whichever target-types those are -- :compose for a partition,
            ;; :local-process for a power-off. Asking for one particular name
            ;; would only be asserting today's table back at itself.
            (let [elsewhere (->> nemesis/validity
                                 (keep (fn [[target-type row]]
                                         (when (get row intent) target-type))))]
              (is (seq elsewhere) (str "nowhere can inject " intent))
              (is (some #(str/includes? msg (str %)) elsewhere)))))))))

(deftest a-nemesis-is-refused-before-the-target-is-even-reached
  ;; Note the URL: nothing is listening there. Validation is static -- it never
  ;; touches the target -- so an impossible fault is refused whether or not the
  ;; target is up, and long before any op is generated.
  (let [e (is (thrown? clojure.lang.ExceptionInfo
                       (core/run {:target  {:type :http
                                            :url   "http://127.0.0.1:1"}
                                  :nemesis [:crash]})))]
    (is (= :invalid-nemesis (:lite/error (ex-data e))))))

;; ## The connection, which is all this target-type owns

(deftest each-worker-gets-its-own-connection
  ;; The other half of the deployment axis: :in-process hands every worker the
  ;; one shared instance, while an external target gives each its own client.
  (with-server :correct
    (fn [{:keys [url]}]
      (let [opens   (atom 0)
            closes  (atom 0)
            adapter (reify client/ClientAdapter
                      (open [_] (swap! opens inc))
                      (invoke [_ _conn op] op)
                      (close [_ _conn] (swap! closes inc)))
            conn    (target/build {:type :http, :url url} adapter)
            workers (mapv (fn [_]
                            (let [seen (promise)
                                  done (promise)]
                              {:seen seen
                               :done done
                               :thread
                               (doto (Thread.
                                      (fn []
                                        (target/acquire! conn)
                                        (deliver seen (target/current conn))
                                        @done
                                        (target/release! conn)))
                                 (.start))}))
                          (range 4))]
        (doseq [w workers] @(:seen w))
        (is (= 4 @opens) "one open per worker")
        (is (= 4 (http/connection-count conn)))
        (is (= 4 (count (distinct (map (comp deref :seen) workers))))
            "and no two workers share a connection")

        (doseq [w workers] (deliver (:done w) true) (.join ^Thread (:thread w)))
        (is (= 4 @closes) "each worker closes its own")
        (is (zero? (http/connection-count conn)))))))

(deftest an-absent-target-is-reported-plainly
  ;; The failure mode this guards against is quiet: with nothing listening,
  ;; every op fails, and a history of nothing but failures is one most checkers
  ;; will call valid. A run that can't mean anything must not start.
  (let [e   (is (thrown? clojure.lang.ExceptionInfo
                         (core/run (http-targets/config
                                    :set "http://127.0.0.1:1"))))
        msg (ex-message e)]
    (is (= :target-unreachable (:lite/error (ex-data e))))
    (is (str/includes? msg "http://127.0.0.1:1"))
    (is (str/includes? msg "why:"))
    (is (str/includes? msg "does not run"))
    (is (str/includes? msg "fix:"))))

(deftest a-url-lite-cannot-connect-to-is-refused
  (doseq [url [nil "not a url" "localhost:8080"]]
    (testing (pr-str url)
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (target/build {:type :http, :url url} nil)))]
        (is (= :invalid-target (:lite/error (ex-data e))))
        (is (str/includes? (ex-message e) "http://localhost:8080"))))))

;; ## HTTP errors, through the wrapper that was already there

(deftest wire-errors-map-onto-the-existing-outcome-contract
  ;; No new classification path: the handler calls the same fail!/info! an
  ;; in-process handler calls, and `lite.client/complete` does the rest.
  (let [invoke (fn [url op]
                 (let [adapter (http-targets/map->HttpAdapter
                                {:url url, :handler (:register
                                                     http-targets/handlers)})
                       conn    (client/open adapter)]
                   (try (client/invoke adapter conn op)
                        (finally (client/close adapter conn)))))
        read   {:type :invoke, :f :read, :key 0, :process 0}]

    (testing "connection refused: certain, so :fail"
      (let [completed (invoke "http://127.0.0.1:1" read)]
        (is (= :fail (:type completed)))
        (is (= :read (:f completed)))
        (is (= 0 (:process completed)))))

    (with-server :correct
      (fn [{:keys [url]}]
        (testing "a rejected op: certain, so :fail"
          (is (= :fail (:type (invoke url {:type  :invoke, :f :cas
                                           :key   0, :value [:nope :other]})))))

        (testing "a served op: :ok"
          (is (= :ok (:type (invoke url read)))))))

    (testing "a timeout: indeterminate, so :info"
      ;; A socket that accepts connections and then says nothing -- a target
      ;; that took the request and never answered, which is the one case Lite
      ;; must never call :ok *or* :fail.
      (with-open [silent (ServerSocket. 0 1 (InetAddress/getByName "127.0.0.1"))]
        (let [adapter   (http-targets/map->HttpAdapter
                         {:url             (str "http://127.0.0.1:"
                                                (.getLocalPort silent))
                          :handler         (:register http-targets/handlers)
                          :request-timeout (Duration/ofMillis 250)})
              conn      (client/open adapter)
              completed (try (client/invoke adapter conn read)
                             (finally (client/close adapter conn)))]
          (is (= :info (:type completed)))
          (is (= :read (:f completed))))))))

(ns lite.nemesis-test
  "M4 acceptance: the crash nemesis perturbs an in-process target, and axis-2
   validation refuses the combinations that can't work."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lite.client :as client]
            [lite.core :as core]
            [lite.nemesis :as nemesis]
            [lite.target :as target]
            [lite.target.in-process :as in-process]
            [lite.targets :as targets]))

;; ## Axis-2 validation

(deftest the-table-covers-every-target-type-and-intent
  (is (= (set target/target-types) (set (keys nemesis/validity))))
  (doseq [[target-type row] nemesis/validity]
    (is (= (set nemesis/intents) (set (keys row)))
        (str target-type " is missing an intent"))))

(deftest in-process-accepts-crash
  (is (= [:crash] (nemesis/validate! :in-process [:crash])))
  (is (nil? (core/validate! (targets/config :set {:nemesis [:crash]})))))

(deftest in-process-refuses-what-it-cannot-do
  (doseq [intent [:pause :partition]]
    (testing intent
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (nemesis/validate! :in-process [intent])))
            msg (ex-message e)]
        (is (= :invalid-nemesis (:lite/error (ex-data e))))
        (testing "says what, why and how to fix it"
          (is (str/includes? msg (str intent)))
          (is (str/includes? msg ":in-process"))
          (is (str/includes? msg "why:"))
          (is (str/includes? msg "fix:"))
          ;; The fix names the alternative that works here, and the
          ;; target-types where the intent the user asked for is possible.
          (is (str/includes? msg "crash"))
          (is (str/includes? msg ":compose")))))))

(deftest validation-happens-before-the-run
  ;; No adapter, no handler, no workload: if this got as far as running
  ;; anything it would fail some other way.
  (let [e (is (thrown? clojure.lang.ExceptionInfo
                       (core/run {:target {:type :in-process}, :nemesis [:pause]})))]
    (is (= :invalid-nemesis (:lite/error (ex-data e))))))

(deftest unknown-names-are-rejected
  (testing "an intent that doesn't exist"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (nemesis/validate! :in-process [:reboot])))]
      (is (= :unknown-nemesis (:lite/error (ex-data e))))))

  (testing "a target-type that doesn't exist"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (core/validate! {:target {:type :carrier-pigeon}})))]
      (is (= :unusable-target-type (:lite/error (ex-data e))))))

  (testing "a target-type in the design but not implemented yet"
    ;; There is no longer one to try: M6 made the last of them runnable, so
    ;; every target-type in `target/target-types` now has a `build` method.
    ;; What remains is the guard for the next one somebody adds to the list
    ;; before implementing it.
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (with-redefs [target/target-types
                                       (conj target/target-types :teleport)]
                           (core/validate! {:target {:type :teleport}}))))]
      (is (= :unusable-target-type (:lite/error (ex-data e))))
      (is (str/includes? (ex-message e) "isn't implemented yet")))))

;; ## The crash nemesis

(defn- crashes
  "Completed crash ops from the nemesis."
  [history]
  (filter (fn [op] (and (= :crash (:f op)) (= :info (:type op)) (:value op)))
          history))

(def durable (delay (core/run (targets/config :set {:nemesis [:crash]}))))

(deftest crashes-happen-repeatedly-during-the-run
  (let [{:keys [history]} @durable
        cs (crashes history)]
    (is (< 1 (count cs)) "one run, many crashes")
    (is (= (range 1 (inc (count cs))) (map :value cs))
        "each crash really did destroy and re-create the instance")
    (is (every? #{:nemesis} (map :process cs)))

    (testing "clients keep working across them"
      ;; If the bridge had captured the instance at open! instead of reading it
      ;; per op, every client op after the first crash would have failed.
      (let [after (filter (fn [op] (< (:time (first cs)) (:time op))) history)]
        (is (some (fn [op] (and (= :ok (:type op)) (number? (:process op))))
                  after))))))

(deftest a-crash-does-not-take-committed-data-with-it
  ;; The heart of M4.5: `open` attaches to durable state, it does not create or
  ;; reset it, so destroying and re-creating the instance is survivable. If
  ;; `open` ever starts seeding again, this is what catches it.
  (let [adapter (assoc (targets/adapter)
                       :handler (fn [kvs {:keys [f key value]}]
                                  (case f
                                    :write (do (swap! kvs assoc key value) value)
                                    :read  (get @kvs key))))
        conn    (target/build {:type :in-process} adapter)
        invoke  (fn [op] (client/invoke adapter (target/current conn) op))]
    (target/acquire! conn)
    (is (= :ok (:type (invoke {:type :invoke, :f :write, :key :k, :value 42}))))

    (in-process/crash! conn)

    (testing "the write survives, and the client follows the new instance"
      (is (= 42 (:value (invoke {:type :invoke, :f :read, :key :k})))))

    (testing "and keeps surviving, crash after crash"
      (dotimes [_ 5] (in-process/crash! conn))
      (is (= 42 (:value (invoke {:type :invoke, :f :read, :key :k}))))
      (is (= 6 (in-process/crash-count conn))))))

(deftest replacement-client-waits-for-graceful-close
  ;; A crash publishes nil while it closes the old instance. Jepsen may replace
  ;; clients whose ops landed in that window; their open! calls must not create a
  ;; second live instance before the old one's graceful close has completed.
  (let [opens         (atom 0)
        close-started (promise)
        allow-close   (promise)
        reopened      (promise)
        adapter       (reify client/ClientAdapter
                        (open [_]
                          (let [instance (swap! opens inc)]
                            (when (> instance 1) (deliver reopened instance))
                            instance))
                        (invoke [_ _conn op] op)
                        (close [_ _conn]
                          (deliver close-started true)
                          @allow-close))
        conn          (target/build {:type :in-process} adapter)]
    (target/acquire! conn)
    (let [crashing  (future (in-process/crash! conn))
          _         @close-started
          acquiring (future (target/acquire! conn))]
      (is (= ::not-opened (deref reopened 100 ::not-opened))
          "a replacement client must not open while graceful close is running")
      (deliver allow-close true)
      @crashing
      @acquiring
      (is (= 2 @opens) "initial startup plus exactly one crash replacement")
      (is (= 2 (target/current conn))
          "every replacement client shares the instance opened by crash!"))))

(deftest a-target-that-survives-crashes-still-checks-out
  (is (true? (:valid? @durable))))

(deftest a-durability-bug-is-caught
  ;; A store that acknowledges writes before they are durable: the defect crash
  ;; testing exists to find, standing in for a missing fsync or an unflushed WAL.
  ;;
  ;; On a clock, with the crashes spread across it. The default schedule fires
  ;; all eight within about fifteen milliseconds, which can be over before the
  ;; workers have opened their clients -- and crashes that all land before the
  ;; first write cost a store nothing, so the run would find no bug because
  ;; there was none to find. That is a flaky test, not a lenient one.
  (let [{:keys [valid? results]}
        (core/run (assoc (targets/config :set {:nemesis    [:crash]
                                               :durability :buggy})
                         :time-limit   1/2
                         :nemesis-opts {:crashes 8, :crash-interval 1/20}))]
    (is (false? valid?))
    (testing "as lost writes, not as an error"
      (is (pos? (:lost-count results)))
      (is (zero? (:unexpected-count results))))))

(deftest without-a-nemesis-nothing-crashes
  (let [{:keys [valid? history]} (core/run (targets/config :set))]
    (is (true? valid?))
    (is (empty? (crashes history)))))

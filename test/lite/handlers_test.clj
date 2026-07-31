(ns lite.handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [lite.client :as client]
            [lite.handlers :as handlers]))

(deftest register-unpacks-ops-and-preserves-checker-values
  (let [calls (atom [])
        handler (handlers/register
                 {:read  (fn [_ k] (swap! calls conj [:read k]) 7)
                  :write (fn [_ k v] (swap! calls conj [:write k v]) :ignored)
                  :cas   (fn [_ k old new]
                           (swap! calls conj [:cas k old new]) true)})]
    (is (= 7 (handler :conn {:f :read, :key 1})))
    (is (= 8 (handler :conn {:f :write, :key 1, :value 8})))
    (is (= [8 9] (handler :conn {:f :cas, :key 1, :value [8 9]})))
    (is (= [[:read 1] [:write 1 8] [:cas 1 8 9]] @calls))))

(deftest register-can-turn-a-false-cas-into-a-certain-failure
  (let [handler (handlers/register
                 {:read (fn [_ _] nil)
                  :write (fn [_ _ _])
                  :cas (fn [_ _ _ _] false)})
        completed (client/complete handler nil
                                   {:type :invoke, :f :cas, :key 2,
                                    :value [1 3]})]
    (is (= :fail (:type completed)))
    (is (= {:type :cas-mismatch, :key 2, :expected 1}
           (:error completed)))))

(deftest collection-and-counter-handlers-normalize-initial-state
  (let [added (atom [])
        set-handler (handlers/set
                     {:add #(swap! added conj %2), :read (fn [_] nil)})
        counter-handler (handlers/counter
                         {:add #(swap! added conj %2), :read (fn [_] nil)})]
    (is (= 4 (set-handler nil {:f :add, :value 4})))
    (is (= [] (set-handler nil {:f :read})))
    (is (= 3 (counter-handler nil {:f :add, :value 3})))
    (is (= 0 (counter-handler nil {:f :read})))
    (is (= [4 3] @added))))

(deftest bank-unpacks-a-transfer
  (let [calls (atom [])
        handler (handlers/bank
                 {:init #(swap! calls conj [:init %2])
                  :read (fn [_] {0 100})
                  :transfer (fn [_ from to amount]
                              (swap! calls conj [:transfer from to amount])
                              amount)})]
    (is (= {0 100} (handler nil {:f :read})))
    (is (= 5 (handler nil {:f :transfer
                           :value {:from 0, :to 1, :amount 5}})))
    (is (= [[:transfer 0 1 5]] @calls))))

(deftest handler-specs-and-operations-are-validated
  (testing "missing operation function"
    (is (thrown? clojure.lang.ExceptionInfo
                 (handlers/set {:read (fn [_] [])}))))
  (testing "operation outside the workload contract"
    (let [handler (handlers/counter
                   {:add (fn [_ _]), :read (fn [_] 0)})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (handler nil {:f :subtract, :value 1}))))))

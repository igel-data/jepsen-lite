(ns lmdb.client
  "The jepsen-lite side: a ClientAdapter that speaks HTTP to `lmdb.driver`,
   and one handler per workload.

   This namespace is the *protocol* axis -- how LMDB is reached. The other
   axis, how it is deployed, is `lmdb.runner`'s `:target`, and the two never
   meet. That is jepsen-lite's design premise, and the reason the same four
   handlers would work unchanged against the driver started by hand.

   Nothing here decides what is correct. It reports what happened, as precisely
   as it can, and the checkers rule."
  (:require [clojure.edn :as edn]
            [lite.client :as client :refer [fail! info!]]
            [lite.handlers :as handlers])
  (:import (java.io IOException)
           (java.net ConnectException URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers
                          HttpTimeoutException)
           (java.time Duration)))

(def ^:private default-request-timeout
  "Generous, because LMDB makes writers queue rather than refusing them.

   `txnWrite` blocks on the single writer lock with no timeout of its own, so
   under a workload of concurrent writers a request legitimately waits its turn.
   Cutting it off here would manufacture an indeterminate op out of a target
   that was working perfectly -- and unlike a refusal, a timeout is an unknown,
   because the write may well be in progress on the other side."
  (Duration/ofSeconds 30))

(def ^:private default-refusal-threshold 8)
(def ^:private default-refusal-backoff-ms 50)

(defn- refused?
  "Did the connection never get made? Then the operation certainly did not
   happen, which is a `fail!` and not an `info!`."
  [^Throwable t]
  (loop [t t]
    (cond (nil? t)                       false
          (instance? ConnectException t) true
          :else                          (recur (.getCause t)))))

(defn- backoff-after-refusal!
  "Stops a dead target from turning a short run into tens of thousands of
   immediate failures. The counter lives on the shared connection, so the
   threshold applies to all workers together rather than once per worker."
  [{:keys [consecutive-refusals refusal-threshold refusal-backoff-ms]}]
  (let [n (swap! consecutive-refusals inc)]
    (when (> n refusal-threshold)
      (Thread/sleep (long refusal-backoff-ms)))))

(defn- post
  "One request to the driver, mapped onto the outcomes a handler can signal:

     2xx                -> the value LMDB returned
     4xx                -> the operation was refused and certainly did not take
                           effect: a CAS mismatch, no funds, a transaction that
                           aborted, an environment already closed
                                                     -> fail!
     5xx                -> something broke midway -- most importantly a failed
                           commit, whose outcome nobody can see
                                                     -> info!
     timeout            -> indeterminate             -> info!
     connection refused -> never arrived, certain    -> fail!
     other I/O          -> indeterminate             -> info!

   The last two are what a `kill -9` mid-run produces: a request that never
   reached a dead driver is a certain failure, and one whose connection died in
   flight may or may not have been committed before the process went. Saying
   `:info` there is not vagueness -- it is the only honest answer, and the
   checkers know what to do with it."
  [{:keys [^HttpClient client url ^Duration timeout consecutive-refusals]
    :as conn} path body]
  (let [request (-> (HttpRequest/newBuilder (URI/create (str url path)))
                    (.timeout timeout)
                    (.header "Content-Type" "application/edn")
                    (.POST (HttpRequest$BodyPublishers/ofString (pr-str body)))
                    (.build))
        ^HttpResponse response
        (try
          (let [response (.send client request
                                (HttpResponse$BodyHandlers/ofString))]
            ;; Any response, including a 4xx/5xx, proves the driver is alive.
            (reset! consecutive-refusals 0)
            response)
          (catch HttpTimeoutException _
            (info! {:timeout path}))
          (catch IOException e
            (if (refused? e)
              (do (backoff-after-refusal! conn)
                  (fail! {:connection-refused path}))
              (info! {:io path, :message (ex-message e)}))))
        status (.statusCode response)
        parsed (edn/read-string (.body response))]
    (cond
      (<= 200 status 299) (:value parsed)
      (<= 400 status 499) (fail! (:error parsed))
      :else               (info! {:status status, :error (:error parsed)}))))

(defn adapter
  "An adapter which connects to the LMDB driver's HTTP API."
  [{:keys [url request-timeout refusal-threshold refusal-backoff-ms]}]
  (client/adapter
   {:open
    (fn []
      {:url     url
       :timeout (or request-timeout default-request-timeout)
       :consecutive-refusals (atom 0)
       :refusal-threshold    (or refusal-threshold default-refusal-threshold)
       :refusal-backoff-ms   (or refusal-backoff-ms default-refusal-backoff-ms)
       :client  (-> (HttpClient/newBuilder)
                    (.connectTimeout (Duration/ofSeconds 2))
                    (.build))})
    :close
    (fn [conn]
      (when-let [c (:client conn)]
        (when (instance? java.lang.AutoCloseable c)
          (.close ^java.lang.AutoCloseable c))))}))

;; ---- handlers --------------------------------------------------------------
;;
;; Each returns what the workload's checker reads off the completed op, which is
;; not always what the driver hands back -- see `:counter` and `:register`.

(def handlers
  "Workload -> the handler that speaks HTTP for it."
  {:register (handlers/register
              {:read #(post %1 "/register/read" {:key %2})
               :write #(post %1 "/register/write" {:key %2, :value %3})
               :cas #(post %1 "/register/cas"
                           {:key %2, :old %3, :new %4})})
   :bank     (handlers/bank
              {:init #(post %1 "/bank/init" {:balances %2})
               :read #(post % "/bank/read" {})
               :transfer #(post %1 "/bank/transfer"
                                {:from %2, :to %3, :amount %4})})
   :set      (handlers/set
              {:add #(post %1 "/set/add" {:element %2})
               :read #(post % "/set/read" {})})
   :counter  (handlers/counter
              {:add #(post %1 "/counter/add" {:amount %2})
               :read #(post % "/counter/read" {})})})

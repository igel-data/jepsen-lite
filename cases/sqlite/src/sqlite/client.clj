(ns sqlite.client
  "The jepsen-lite side: a ClientAdapter that speaks HTTP to `sqlite.driver`,
   and one handler per workload.

   This namespace is the *protocol* axis -- how SQLite is reached. The other
   axis, how it is deployed, is `sqlite.runner`'s `:target`, and the two never
   meet. That is jepsen-lite's design premise, and the reason the same four
   handlers would work unchanged against the driver started by hand.

   Nothing here decides what is correct. It reports what happened, as precisely
   as it can, and the checkers rule."
  (:require [clojure.edn :as edn]
            [lite.client :as client :refer [fail! info!]])
  (:import (java.io IOException)
           (java.net ConnectException URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers
                          HttpTimeoutException)
           (java.time Duration)))

(def ^:private default-request-timeout
  "Long enough that a healthy SQLite never trips it -- comfortably past the
   driver's own `busy_timeout`, so lock contention resolves into a 409 rather
   than being cut off here and recorded as an unknown."
  (Duration/ofSeconds 10))

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

     2xx                -> the value SQLite returned
     4xx                -> the operation was refused and certainly did not take
                           effect: a CAS mismatch, no funds, a write lock never
                           acquired                  -> fail!
     5xx                -> something broke midway -- most importantly a failed
                           COMMIT, whose outcome nobody can see
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

(defrecord Adapter [handler url request-timeout
                    refusal-threshold refusal-backoff-ms]
  client/ClientAdapter
  (open [_]
    ;; A client against the running driver. Note what is absent: nothing is
    ;; created, started or seeded here. The database was on disk before this
    ;; connection existed -- which is what makes reopening it after a crash a
    ;; question worth asking.
    {:url     url
     :timeout (or request-timeout default-request-timeout)
     :consecutive-refusals (atom 0)
     :refusal-threshold    (or refusal-threshold default-refusal-threshold)
     :refusal-backoff-ms   (or refusal-backoff-ms default-refusal-backoff-ms)
     :client  (-> (HttpClient/newBuilder)
                  (.connectTimeout (Duration/ofSeconds 2))
                  (.build))})

  (invoke [_ conn op]
    (client/complete handler conn op))

  (close [_ conn]
    ;; Tolerate a nil or already-closed conn: `close` is re-runnable.
    (when-let [c (:client conn)]
      (when (instance? java.lang.AutoCloseable c)
        (.close ^java.lang.AutoCloseable c)))))

;; ---- handlers --------------------------------------------------------------
;;
;; Each returns what the workload's checker reads off the completed op, which is
;; not always what the driver hands back -- see `:counter` and `:register`.

(defn- register-handler
  [conn {:keys [f key value]}]
  (case f
    :read  (post conn "/register/read" {:key key})
    :write (do (post conn "/register/write" {:key key, :value value})
               value)
    :cas   (let [[old new] value]
             ;; A mismatch comes back 409 and `post` calls fail! -- an ordinary
             ;; failed op, not a violation. On success return the pair the op
             ;; carried: Knossos's model reads `[old new]` off the completed op.
             (post conn "/register/cas" {:key key, :old old, :new new})
             value)))

(defn- set-handler
  [conn {:keys [f value]}]
  (case f
    :add  (post conn "/set/add" {:element value})
    :read (or (post conn "/set/read" {}) [])))

(defn- counter-handler
  [conn {:keys [f value]}]
  (case f
    ;; Return the increment, not the running total the driver hands back: the
    ;; checker sums the amounts on the completed ops.
    :add  (do (post conn "/counter/add" {:amount value})
              value)
    :read (long (or (post conn "/counter/read" {}) 0))))

(defn- bank-handler
  [conn {:keys [f value]}]
  (case f
    ;; The opening balances, from the workload's first phase, over the wire like
    ;; any other op. The handler doesn't know they are special, and SQLite
    ;; doesn't know it is a bank.
    :init     (post conn "/bank/init" {:balances value})
    :read     (post conn "/bank/read" {})
    :transfer (post conn "/bank/transfer" value)))

(def handlers
  "Workload -> the handler that speaks HTTP for it."
  {:register register-handler
   :bank     bank-handler
   :set      set-handler
   :counter  counter-handler})

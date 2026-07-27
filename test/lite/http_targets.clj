(ns lite.http-targets
  "A minimal HTTP key-value store, and the user-side code that talks to it, for
   the `:http` tests to run against.

   Two halves, and the split is the point of M5:

   1. **The server** is the target. It runs outside Lite -- here, started by the
      test fixture, in a milestone or two by docker-compose -- and Lite has no
      power over it beyond opening connections. One variant behaves; one has
      deliberate defects, so the checkers have something to catch.

   2. **The adapter and handlers** are what a Lite user writes. They are the
      same shape as the in-process ones in `lite.targets`: a ClientAdapter with
      open/invoke/close, and one handler per workload. What changed is only the
      body of the calls -- an HTTP round trip instead of a `swap!` -- which is
      exactly the claim M5 is testing.

   The wire format is EDN, so that a value the workload wrote comes back as the
   value it wrote. JSON would turn the bank's integer account keys into strings
   and the argument would be about encodings rather than about orthogonality."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [lite.client :as client :refer [fail! info!]])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io IOException)
           (java.net ConnectException InetSocketAddress URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers
                          HttpTimeoutException)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent Executors)))

;; ## The store's operations
;;
;; A single map behind an atom, reached through a handful of key-value
;; endpoints. `swap!` gives every one of them the atomicity a real store would
;; owe its clients -- including the multi-key transfer `:bank` needs.

(defrecord Rejected [reason])

(defn- reject
  "The store refusing an operation: a CAS that didn't match, a transfer with no
   funds. Answered with 409, and certain -- the operation did not happen."
  [reason]
  (->Rejected reason))

(defn- cas!
  [store {:keys [key old new]}]
  (let [[before _after] (swap-vals! store (fn [m]
                                            (if (= (get m key) old)
                                              (assoc m key new)
                                              m)))]
    ;; Compare on `before` rather than on whether the map changed: a CAS from a
    ;; value to itself is a success that changes nothing.
    (if (= (get before key) old)
      new
      (reject "cas mismatch"))))

(defn- broken-cas!
  "The defect: writes `new` whichever value the register actually holds. Every
   CAS 'succeeds', including ones that should have failed, so the register
   takes on values no linearization can justify."
  [store {:keys [key new]}]
  (swap! store assoc key new)
  new)

(defn- append!
  [store {:keys [key element]}]
  (swap! store update key (fnil conj []) element)
  element)

(defn- broken-append!
  "The defect: silently drops every fifth element. The append is acknowledged,
   so the checker counts the element as one the target promised to keep."
  [store {:keys [key element]}]
  (when-not (zero? (mod element 5))
    (swap! store update key (fnil conj []) element))
  element)

(defn- add!
  [store {:keys [key amount]}]
  (get (swap! store update key (fnil + 0) amount) key))

(defn- broken-add!
  "The defect: credits only half of each increment, rounding down, so the
   counter drifts far below the sum of the increments it acknowledged."
  [store {:keys [key amount]}]
  (get (swap! store update key (fnil + 0) (quot amount 2)) key))

(defn- transfer!
  "Debits and credits in one atomic step -- the one thing `:bank` asks of a
   target, and the reason this is a server-side endpoint rather than two calls
   from the handler."
  [store {:keys [from to amount]}]
  (let [[before after] (swap-vals! store (fn [m]
                                           (if (<= amount (get m from 0))
                                             (-> m (update from - amount)
                                                 (update to + amount))
                                             m)))]
    (if (= before after)
      (reject "insufficient funds")
      amount)))

(defn- broken-transfer!
  "The defect: debits the sender and never credits the recipient, so every
   transfer destroys money and the total stops adding up."
  [store {:keys [from amount]}]
  (let [[before after] (swap-vals! store (fn [m]
                                           (if (<= amount (get m from 0))
                                             (update m from - amount)
                                             m)))]
    (if (= before after)
      (reject "insufficient funds")
      amount)))

(defn- routes
  "path -> (fn [store request] value-or-Rejected). Reads and writes are the
   same in both variants; the four operations a workload can catch out are
   swapped for their broken selves."
  [{:keys [cas append add transfer]}]
  {"/read"      (fn [store {:keys [key]}] (get @store key))
   "/read-all"  (fn [store _req] @store)
   "/write"     (fn [store {:keys [key value]}]
                  (swap! store assoc key value)
                  value)
   "/write-all" (fn [store {:keys [values]}]
                  (swap! store merge values)
                  values)
   "/cas"       cas
   "/append"    append
   "/add"       add
   "/transfer"  transfer})

(def variants
  "A store that behaves, and one with defects a checker should catch."
  {:correct {:cas      cas!
             :append   append!
             :add      add!
             :transfer transfer!}
   :broken  {:cas      broken-cas!
             :append   broken-append!
             :add      broken-add!
             :transfer broken-transfer!}})

;; ## The server

(defn- request-body
  [^HttpExchange exchange]
  (with-open [in (.getRequestBody exchange)]
    (let [body (slurp in :encoding "UTF-8")]
      (if (str/blank? body) {} (edn/read-string body)))))

(defn- respond!
  [^HttpExchange exchange status body]
  (let [bytes (.getBytes (pr-str body) StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "Content-Type" "application/edn")
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- exchange-handler
  [store paths]
  (reify HttpHandler
    (handle [_this exchange]
      (with-open [^HttpExchange ex exchange]
        (try
          (let [path (.getPath (.getRequestURI ex))
                op   (get paths path)]
            (cond
              (not= "POST" (.getRequestMethod ex))
              (respond! ex 405 {:error (str "use POST for " path)})

              (nil? op)
              (respond! ex 404 {:error (str "no such operation " path)})

              :else
              (let [result (op store (request-body ex))]
                (if (instance? Rejected result)
                  (respond! ex 409 {:error (:reason result)})
                  (respond! ex 200 {:value result})))))
          (catch Throwable t
            ;; Never leave a client waiting on a response that isn't coming.
            (try
              (respond! ex 500 {:error (str (.getName (class t)) ": "
                                            (ex-message t))})
              (catch IOException _ nil))))))))

(defn server
  "Starts an HTTP KVS and returns a handle: `{:url ..., :store ..., :stop ...}`.

   Note who calls this: the test fixture, not the `:http` target-type. Lite
   connects to a target that is already up; bringing one up is somebody else's
   job, which here means the harness's.

   Options: `:variant` :correct (default) or :broken, `:port` (0 for any free
   one)."
  ([] (server {}))
  ([{:keys [variant port] :or {variant :correct, port 0}}]
   (let [store  (atom {})
         pool   (Executors/newCachedThreadPool)
         server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
     ;; Without an executor every request is served on the one dispatcher
     ;; thread, and a store that answers one client at a time can't be caught
     ;; out by a workload -- least of all `:bank`, whose whole question is what
     ;; concurrent clients see.
     (.setExecutor server pool)
     (.createContext server "/" (exchange-handler
                                 store (routes (get variants variant))))
     (.start server)
     {:url   (str "http://127.0.0.1:" (.getPort (.getAddress server)))
      :store store
      :stop  (fn []
               (.stop server 0)
               (.shutdownNow pool)
               nil)})))

;; ## The user's side: one adapter, one handler per workload
;;
;; Everything below is code a Lite user writes. It knows the target's protocol
;; -- URLs, verbs, status codes -- and nothing about how the target is
;; deployed. It never mentions `:http`, a target-type, or a nemesis.

(def ^:private default-request-timeout
  "Long enough that a healthy loopback server never trips it."
  (Duration/ofSeconds 5))

(defn- refused?
  "Did the connection never get made? Then the operation certainly did not
   happen, which is a `fail!` and not an `info!`."
  [^Throwable t]
  (loop [t t]
    (cond (nil? t)                       false
          (instance? ConnectException t) true
          :else                          (recur (.getCause t)))))

(defn- post
  "One request to the target, and the outcome contract Lite already has:

     2xx                -> the value the target returned
     4xx                -> the target rejected the op; it certainly did not
                           take effect             -> fail!
     5xx                -> the target broke somewhere in the middle; whether
                           the op took effect is unknowable -> info!
     timeout            -> indeterminate           -> info!
     connection refused -> never arrived, certain  -> fail!
     other I/O          -> indeterminate           -> info!

   Note what this is not: a second error-classification path. `fail!` and
   `info!` are the same two functions an in-process handler calls, and Lite's
   wrapper turns them into `:fail` and `:info` without knowing HTTP exists."
  [{:keys [^HttpClient client url ^Duration timeout]} path body]
  (let [request (-> (HttpRequest/newBuilder (URI/create (str url path)))
                    (.timeout timeout)
                    (.header "Content-Type" "application/edn")
                    (.POST (HttpRequest$BodyPublishers/ofString (pr-str body)))
                    (.build))
        ^HttpResponse response
        (try
          (.send client request (HttpResponse$BodyHandlers/ofString))
          (catch HttpTimeoutException _
            (info! {:timeout path}))
          (catch IOException e
            (if (refused? e)
              (fail! {:connection-refused path})
              (info! {:io path, :message (ex-message e)}))))
        status (.statusCode response)
        parsed (edn/read-string (.body response))]
    (cond
      (<= 200 status 299) (:value parsed)
      (<= 400 status 499) (fail! (:error parsed))
      :else               (info! {:status status, :error (:error parsed)}))))

(defrecord HttpAdapter [handler url request-timeout]
  client/ClientAdapter
  (open [_]
    ;; A client of this worker's own against the running target. Note what is
    ;; absent, exactly as in-process: nothing is created, started or seeded
    ;; here. The target and its data were there before Lite connected.
    {:url     url
     :timeout (or request-timeout default-request-timeout)
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

;; ## The handlers -- one per workload's contract
;;
;; There is only one set: the defects live in the server now, not here. That is
;; itself worth noticing. In-process the handler *was* the target, so breaking
;; the target meant breaking the handler. Over HTTP the target is a program of
;; its own, so the same handler drives both the correct store and the broken
;; one, and the checker's verdict is about the store.

(defn- register-handler
  [conn {:keys [f key value]}]
  (case f
    :read  (post conn "/read" {:key key})
    :write (post conn "/write" {:key key, :value value})
    :cas   (let [[old new] value]
             ;; A mismatch comes back 409 and `post` calls fail! -- an ordinary
             ;; failed op, not a violation. On success return the pair the op
             ;; carried, exactly as the in-process handler does: the checker's
             ;; model reads `[old new]` off the completed op.
             (post conn "/cas" {:key key, :old old, :new new})
             value)))

(defn- set-handler
  [conn {:keys [f value]}]
  (case f
    :add  (post conn "/append" {:key :elements, :element value})
    :read (or (post conn "/read" {:key :elements}) [])))

(defn- counter-handler
  [conn {:keys [f value]}]
  (case f
    ;; Return the increment, not the running total the server hands back: the
    ;; checker reads the amount off the completed op, the same as in-process.
    :add  (do (post conn "/add" {:key :counter, :amount value})
              value)
    :read (long (or (post conn "/read" {:key :counter}) 0))))

(defn- bank-handler
  [conn {:keys [f value]}]
  (case f
    ;; The opening balances, from the workload's first phase, over the wire
    ;; like any other op. The handler does not know they are special.
    :init     (post conn "/write-all" {:values value})
    :read     (post conn "/read-all" {})
    :transfer (post conn "/transfer" value)))

(def handlers
  "Workload -> the handler that speaks HTTP for it."
  {:register register-handler
   :set      set-handler
   :counter  counter-handler
   :bank     bank-handler})

(defn config
  "A run config for one workload against an HTTP KVS at `url`. Compare it with
   `lite.targets/config`: same `:workload` values, same handler contract, a
   different `:target`."
  [workload url]
  (let [handler (get handlers workload)]
    (assert handler (str "No HTTP handler for workload " (pr-str workload)))
    {:adapter  (map->HttpAdapter {:url url})
     :handler  handler
     :workload workload
     :name     (str "jepsen-lite-test-http-" (name workload))
     :target   {:type :http, :url url}}))

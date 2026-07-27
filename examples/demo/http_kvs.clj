(ns demo.http-kvs
  "The same four workloads as `demo.kvs`, against a key-value store running
   *outside* Lite and reached over HTTP.

   Start the store in one terminal and point Lite at it from another:

     clojure -M:serve                        # terminal 1: the target
     clojure -M:run-http register            # terminal 2: Lite
     clojure -M:run-http bank time=10 concurrency=8

     clojure -M:serve broken                 # terminal 1: a store with defects
     clojure -M:run-http bank broken         # ... which the checker catches

     clojure -M:run-http set crash           # refused: see below

   Read this next to `demo/kvs.clj`, which does the same thing against an
   in-process store. The differences are the whole of what a new target-type
   costs a user:

     - the handlers make an HTTP call where the in-process ones called `swap!`
     - `open` builds an HTTP client where the in-process one attached to an atom
     - the config says `:target {:type :http, :url ...}`

   And what stays the same: the `:workload` values, the handler contract each
   workload documents, the `fail!`/`info!` outcome signalling, the checkers,
   the verdict. Neither the workloads nor Lite's insides know which of these
   two demos is running.

   One thing an `:http` target cannot do is take a nemesis. Lite connects to
   this store; it does not run it, so it has nothing to crash. Asking anyway
   (`crash` above) stops the run before it starts and says so."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [lite.client :as client :refer [fail! info!]]
            [lite.core :as core])
  (:import (java.io IOException)
           (java.net ConnectException URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers
                          HttpTimeoutException)
           (java.time Duration)))

;; ## The adapter
;;
;; One adapter serves every workload, as in-process. It knows the store's
;; calling convention and the connection lifecycle, and nothing about which
;; workload is running or about how the store came to be running.

(def ^:private default-request-timeout
  "Long enough that a healthy store never trips it."
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
  "One request to the store, mapped onto the outcomes a handler can signal:

     2xx                -> the value the store returned
     4xx                -> the store rejected the op; it certainly did not take
                           effect                  -> fail!
     5xx                -> the store broke midway; whether the op took effect
                           is unknowable           -> info!
     timeout            -> indeterminate           -> info!
     connection refused -> never arrived, certain  -> fail!
     other I/O          -> indeterminate           -> info!

   Note what this is *not*: a second error-classification path. `fail!` and
   `info!` are the same two functions the in-process demo calls, and Lite turns
   them into `:fail` and `:info` without knowing that HTTP exists."
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

(defrecord Adapter [handler url request-timeout]
  client/ClientAdapter
  (open [_]
    ;; A client of this worker's own against the running store. Note what is
    ;; absent, exactly as in-process: nothing is created, started or seeded
    ;; here. The store and its data were there before Lite connected, and will
    ;; outlive the run.
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
;; There is only one set of them, where the in-process demo had a correct and a
;; broken variant of each. That is worth a moment: in-process the handler *was*
;; the store, so breaking the store meant breaking the handler. Here the store
;; is a program of its own, so the same handler drives both the correct one and
;; the broken one, and the verdict is about the store rather than about the
;; code that called it.

(defn- register-handler
  [conn {:keys [f key value]}]
  (case f
    :read  (post conn "/read" {:key key})
    :write (post conn "/write" {:key key, :value value})
    :cas   (let [[old new] value]
             ;; A mismatch comes back 409 and `post` calls fail! -- an ordinary
             ;; failed op, not a violation. On success return the pair the op
             ;; carried: the checker's model reads `[old new]` off the
             ;; completed op.
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
    ;; Return the increment, not the running total the store hands back: the
    ;; checker reads the amount off the completed op.
    :add  (do (post conn "/add" {:key :counter, :amount value})
              value)
    :read (long (or (post conn "/read" {:key :counter}) 0))))

(defn- bank-handler
  [conn {:keys [f value]}]
  (case f
    ;; The opening balances, from the workload's first phase, over the wire
    ;; like any other op. The handler doesn't know they are special, and the
    ;; store doesn't know it is a bank.
    :init     (post conn "/write-all" {:values value})
    :read     (post conn "/read-all" {})
    :transfer (post conn "/transfer" value)))

(def handlers
  "Workload -> the handler that speaks HTTP for it."
  {:register register-handler
   :set      set-handler
   :counter  counter-handler
   :bank     bank-handler})

;; ## The demos

(def default-url "http://127.0.0.1:8080")

(defn config
  "A run config for one workload against the store at `url`. Options:

     :url         where the store is listening (default http://127.0.0.1:8080)
     :nemesis     faults to inject -- there are none for :http, and asking is
                  how you see the refusal
     :time-limit  how many seconds to run for
     :concurrency how many workers to run"
  ([workload] (config workload {}))
  ([workload {:keys [url nemesis time-limit concurrency]
              :or   {url default-url}}]
   (let [handler (get handlers workload)]
     (assert handler (str "No handler for workload " (pr-str workload)))
     (cond-> {:adapter  (map->Adapter {:url url})
              :handler  handler
              :workload workload
              :name     (str "jepsen-lite-demo-http-" (name workload))
              ;; The one line that differs from the in-process demo. The url
              ;; appears twice because the two axes each need it for their own
              ;; reason: the adapter has to *call* the store, and the
              ;; target-type has to know where the store is supposed to be so
              ;; it can say plainly when nothing is there.
              :target   {:type :http, :url url}}
       time-limit  (assoc :time-limit time-limit)
       concurrency (assoc :concurrency concurrency)
       nemesis     (assoc :nemesis nemesis)))))

(defn- clear!
  "Empties the store before a run.

   Preparing the target is the user's job with an `:http` target: Lite connects
   to something already running, and never touches its lifecycle or its data.
   One store serves every workload and every run here, so without this a run
   would be checking the leftovers of the last one -- a `:set` read would find
   elements from a previous run and call them phantoms."
  [url]
  (let [adapter (map->Adapter {:url url})
        conn    (client/open adapter)]
    (try
      (post conn "/reset" {})
      (catch clojure.lang.ExceptionInfo e
        (println (str "\nCouldn't prepare the store at " url ".\n\n"
                      "  why: " (pr-str (:lite/detail (ex-data e)
                                                      (ex-message e))) "\n\n"
                      "  fix: start one with `clojure -M:serve` (or"
                      " `clojure -M:serve broken`) and point this at it with"
                      " url=..."))
        (shutdown-agents)
        (System/exit 2))
      (finally
        (client/close adapter conn)))))

(defn- parse-args
  "Words in any order: a workload name, any of broken / crash, and settings as
   key=value, e.g. url=http://127.0.0.1:9090 time=10 concurrency=4."
  [args]
  (let [flags    (set (remove #(str/includes? % "=") args))
        settings (into {} (map #(str/split % #"=" 2))
                       (filter #(str/includes? % "=") args))
        number   (fn [k] (some-> (get settings k) parse-long))]
    [(or (first (filter (comp flags name) (keys handlers))) :register)
     (cond-> {}
       (get settings "url")   (assoc :url (get settings "url"))
       ;; Not something Lite does to the store -- it says which store you
       ;; started in the other terminal, and so which verdict to expect.
       (flags "broken")       (assoc :expect :invalid)
       (flags "crash")        (assoc :nemesis [:crash])
       (number "time")        (assoc :time-limit (number "time"))
       (number "concurrency") (assoc :concurrency (number "concurrency")))]))

(defn -main
  "`clojure -M:run-http [workload] [broken] [crash] [url=...] [time=s]
   [concurrency=n]`, with `clojure -M:serve` running in another terminal.

   Exits non-zero if the verdict isn't the one the demo is meant to produce,
   and 2 if Lite refused the run."
  [& args]
  (let [[workload opts] (parse-args args)
        _      (clear! (:url opts default-url))
        result (try
                 (core/run (config workload opts))
                 (catch clojure.lang.ExceptionInfo e
                   ;; Lite's own refusals -- an impossible fault, a store that
                   ;; isn't there -- explain themselves; a stack trace on top
                   ;; would only bury the explanation.
                   (if (:lite/error (ex-data e))
                     (do (println (str "\n" (ex-message e)))
                         (shutdown-agents)
                         (System/exit 2))
                     (throw e))))
        expected (not= :invalid (:expect opts))
        labels   (cond-> [(name workload) "over http"]
                   (= :invalid (:expect opts)) (conj "(broken store)"))]
    (println (str "\n" (str/join " " labels) ": :valid? "
                  (pr-str (:valid? result))))
    (shutdown-agents)
    (System/exit (if (= (:valid? result) expected) 0 1))))

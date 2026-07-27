(ns lite.http-targets
  "A minimal HTTP key-value store, and the user-side code that talks to it, for
   the `:http` tests to run against.

   Two halves, and the split is the point of M5:

   1. **The server** is the target. It runs outside Lite -- started by the test
      fixture in-process for `:http`, and started *by Lite as a separate OS
      process* for `:local-process`. The same server serves both: what differs
      is who owns its lifetime, which is the whole of the second axis. One
      variant behaves; one has deliberate defects, so the checkers have
      something to catch.

   2. **The adapter and handlers** are what a Lite user writes. They are the
      same shape as the in-process ones in `lite.targets`: a ClientAdapter with
      open/invoke/close, and one handler per workload. What changed is only the
      body of the calls -- an HTTP round trip instead of a `swap!` -- which is
      exactly the claim M5 tested, and they are untouched by M6.

   Given a `:data-dir` the store is **persistent**: every mutation is appended
   to a log and the log is replayed at startup, so a store that is killed and
   started again comes back with what it acknowledged. That is what makes a
   real `kill -9` a question worth asking.

   The wire format is EDN, so that a value the workload wrote comes back as the
   value it wrote. JSON would turn the bank's integer account keys into strings
   and the argument would be about encodings rather than about orthogonality."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [lite.client :as client :refer [fail! info!]])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io BufferedWriter FileOutputStream IOException
                    OutputStreamWriter)
           (java.net ConnectException InetSocketAddress URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers
                          HttpTimeoutException)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent Executors)))

;; ## The store's operations
;;
;; Each is a pure state transition -- `(fn [state request] [state' value])` --
;; which is what lets the server log a mutation before it publishes it. The
;; server runs mutations one at a time, so every one of these is atomic,
;; including the multi-key transfer `:bank` needs.

(defrecord Rejected [reason])

(defn- reject
  "The store refusing an operation: a CAS that didn't match, a transfer with no
   funds. Answered with 409, and certain -- the operation did not happen."
  [state reason]
  [state (->Rejected reason)])

(defn- cas
  [state {:keys [key old new]}]
  ;; A CAS from a value to itself is a success that changes nothing.
  (if (= (get state key) old)
    [(assoc state key new) new]
    (reject state "cas mismatch")))

(defn- broken-cas
  "The defect: writes `new` whichever value the register actually holds. Every
   CAS 'succeeds', including ones that should have failed, so the register
   takes on values no linearization can justify."
  [state {:keys [key new]}]
  [(assoc state key new) new])

(defn- append
  [state {:keys [key element]}]
  [(update state key (fnil conj []) element) element])

(defn- broken-append
  "The defect: silently drops every fifth element. The append is acknowledged,
   so the checker counts the element as one the target promised to keep."
  [state {:keys [key element]}]
  [(if (zero? (mod element 5))
     state
     (update state key (fnil conj []) element))
   element])

(defn- add
  [state {:keys [key amount]}]
  (let [state' (update state key (fnil + 0) amount)]
    [state' (get state' key)]))

(defn- broken-add
  "The defect: credits only half of each increment, rounding down, so the
   counter drifts far below the sum of the increments it acknowledged."
  [state {:keys [key amount]}]
  (let [state' (update state key (fnil + 0) (quot amount 2))]
    [state' (get state' key)]))

(defn- transfer
  "Debits and credits in one step -- the one thing `:bank` asks of a target,
   and the reason this is a server-side endpoint rather than two calls from the
   handler."
  [state {:keys [from to amount]}]
  (if (<= amount (get state from 0))
    [(-> state (update from - amount) (update to + amount)) amount]
    (reject state "insufficient funds")))

(defn- broken-transfer
  "The defect: debits the sender and never credits the recipient, so every
   transfer destroys money and the total stops adding up."
  [state {:keys [from amount]}]
  (if (<= amount (get state from 0))
    [(update state from - amount) amount]
    (reject state "insufficient funds")))

(defn- routes
  "path -> (fn [state request] [state' value-or-Rejected]). Reads and plain
   writes are the same in both variants; the four operations a workload can
   catch out are swapped for their broken selves."
  [{:keys [cas append add transfer]}]
  {"/read"      (fn [state {:keys [key]}] [state (get state key)])
   "/read-all"  (fn [state _req] [state state])
   "/write"     (fn [state {:keys [key value]}] [(assoc state key value) value])
   "/write-all" (fn [state {:keys [values]}] [(merge state values) values])
   "/cas"       cas
   "/append"    append
   "/add"       add
   "/transfer"  transfer})

(def variants
  "A store that behaves, and one with defects a checker should catch."
  {:correct {:cas      cas
             :append   append
             :add      add
             :transfer transfer}
   :broken  {:cas      broken-cas
             :append   broken-append
             :add      broken-add
             :transfer broken-transfer}})

;; ## Persistence
;;
;; A log of what changed, appended before the change is visible and replayed at
;; startup. Two durabilities, and the difference between them is the whole
;; question a `kill -9` asks:
;;
;;   :durable   flush and fsync before acknowledging. What was acknowledged is
;;              on disk, so it survives.
;;   :buffered  acknowledge first and let the writer's buffer flush when it
;;              feels like it. This is a real bug class -- a store keeping
;;              acknowledged writes in its own memory -- and it is one SIGKILL
;;              can catch: the buffer dies with the process. (Writes that
;;              reached the *kernel* unfsynced would survive a SIGKILL; losing
;;              those needs power loss, or a filesystem fault injector.)

(defn- open-log!
  [dir durability]
  (let [dir  (doto (io/file dir) (.mkdirs))
        file (io/file dir "kvs.log")
        out  (FileOutputStream. file true)]
    {:file       file
     :stream     out
     :writer     (BufferedWriter. (OutputStreamWriter. out StandardCharsets/UTF_8))
     :durability durability}))

(defn- log-append!
  [{:keys [^BufferedWriter writer ^FileOutputStream stream durability]} entry]
  (.write writer ^String (pr-str entry))
  (.write writer "\n")
  (when (= :durable durability)
    (.flush writer)
    (.sync (.getFD stream))))

(defn- replay
  "The state the log leaves behind. A torn last line is what a process killed
   mid-write leaves, and a store that couldn't read past it would be worse than
   the crash: stop there and keep everything before it, as a WAL does."
  [file]
  (if-not (.isFile (io/file file))
    {}
    (with-open [reader (io/reader file)]
      (reduce (fn [state line]
                (if (str/blank? line)
                  state
                  (if-let [entry (try (edn/read-string line)
                                      (catch RuntimeException _ nil))]
                    (merge state entry)
                    (reduced state))))
              {}
              (line-seq reader)))))

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

(defn- apply-op!
  "Runs one operation: log what it changed, then publish it.

   Mutations are serialized, which is what makes them atomic -- a `:bank` read
   never catches a transfer half-done. Reads take the atom's value and need no
   lock, so they stay concurrent with each other and with writes."
  [{:keys [state log write-lock]} op request]
  (let [snapshot       @state
        [state' value] (op snapshot request)]
    (if (identical? snapshot state')
      ;; A read, or an operation the store refused: nothing to publish, and
      ;; nothing to make durable either.
      value
      (locking write-lock
        ;; Run it again against what is there now: the snapshot above was taken
        ;; without the lock, and a mutation has to build on the current state.
        (let [current        @state
              [state' value] (op current request)
              changed        (into {}
                                   (remove (fn [[k v]] (= v (get current k))))
                                   state')]
          ;; Log first, then publish -- a value a client can read must already
          ;; be one a restart can recover.
          (when log (log-append! log changed))
          (reset! state state')
          value)))))

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
              (let [result (apply-op! store op (request-body ex))]
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

   Note who calls this: the test fixture or Lite's `:local-process` target-type
   -- never the `:http` one, which connects to a target that is already up.

   Options:

     :variant     :correct (default) or :broken
     :port        0 (default) for any free one
     :data-dir    where to keep the log; omitted means the store is in memory
                  only and a restart comes back empty
     :durability  :durable (default) or :buffered -- see Persistence, above"
  ([] (server {}))
  ([{:keys [variant port data-dir durability]
     :or   {variant :correct, port 0, durability :durable}}]
   (let [log    (when data-dir (open-log! data-dir durability))
         ;; Recovery: whatever the log says was committed. Note what is absent
         ;; -- any wiping or seeding. A store that reset itself on startup
         ;; would pass a crash test by forgetting the question.
         state  (atom (if log (replay (:file log)) {}))
         pool   (Executors/newCachedThreadPool)
         server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
     ;; Without an executor every request is served on the one dispatcher
     ;; thread, and a store that answers one client at a time can't be caught
     ;; out by a workload -- least of all `:bank`, whose whole question is what
     ;; concurrent clients see.
     (.setExecutor server pool)
     (.createContext server "/" (exchange-handler
                                 {:state state, :log log, :write-lock (Object.)}
                                 (routes (get variants variant))))
     (.start server)
     {:url   (str "http://127.0.0.1:" (.getPort (.getAddress server)))
      :store state
      :stop  (fn []
               (.stop server 0)
               (.shutdownNow pool)
               (when log (.close ^BufferedWriter (:writer log)))
               nil)})))

(defn -main
  "Runs the store as a program of its own, which is what a `:local-process`
   target is: `--port n --data-dir path --variant broken --durability buffered`.

   Lite starts this; it does not import it. The process it kills is this one."
  [& args]
  (let [opts (into {} (map (fn [[k v]] [(keyword (str/replace k #"^--" "")) v]))
                   (partition 2 args))
        {:keys [url]} (server {:port       (parse-long (:port opts "0"))
                               :data-dir   (:data-dir opts)
                               :variant    (keyword (:variant opts "correct"))
                               :durability (keyword (:durability opts "durable"))})]
    (println (str "kvs listening on " url))
    (flush)
    @(promise)))

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

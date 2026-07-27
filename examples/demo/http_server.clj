(ns demo.http-server
  "A minimal HTTP key-value store: the *target* for the `:http` demo.

   This is not Jepsen Lite, and it does not require Jepsen Lite. It is a
   program you run in another terminal, the way you would run the database you
   actually wanted to test. Lite connects to it, and that is the whole of their
   relationship -- Lite never starts it, stops it, or restarts it, which is why
   an `:http` target can't be given a nemesis.

     clojure -M:serve                 # a store that behaves
     clojure -M:serve broken          # one with defects, to catch
     clojure -M:serve port=9090

   The API is the smallest one the four workloads need. It speaks EDN so that a
   value a workload wrote comes back as the value it wrote:

     POST /read       {:key k}                    -> {:value v}
     POST /read-all   {}                          -> {:value {k v, ...}}
     POST /reset      {}                          -> {:value {}}
     POST /write      {:key k, :value v}          -> {:value v}
     POST /write-all  {:values {k v, ...}}        -> {:value {k v, ...}}
     POST /cas        {:key k, :old o, :new n}    -> {:value n} | 409
     POST /append     {:key k, :element e}        -> {:value e}
     POST /add        {:key k, :amount n}         -> {:value total}
     POST /transfer   {:from a, :to b, :amount n} -> {:value n} | 409

   409 means the store refused the operation -- a CAS that didn't match, a
   transfer with no funds. It certainly did not happen, and the client side
   turns it into an ordinary `:fail`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io IOException)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent Executors)))

;; ## The store's operations
;;
;; One map behind an atom, reached through a handful of key-value endpoints.
;; `swap!` gives each of them the atomicity a real store owes its clients --
;; including the multi-key transfer that `:bank` exists to test.

(defrecord Rejected [reason])

(defn- reject
  "The store refusing an operation. Answered with 409: certain, not a crash."
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
  "The defect: silently drops every fifth element, and acknowledges it anyway,
   so the client is promised an element the store never kept."
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
   store, and the reason this is an endpoint of its own rather than two calls
   from the client."
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
  "path -> (fn [store request] value-or-Rejected). Reads and plain writes are
   the same in both variants; the four operations a workload can catch out are
   swapped for their broken selves."
  [{:keys [cas append add transfer]}]
  {"/read"      (fn [store {:keys [key]}] (get @store key))
   "/read-all"  (fn [store _req] @store)
   ;; One long-running store serves every workload and every run, so there has
   ;; to be some way to empty it. Note whose call that is: the client's, before
   ;; a run -- Lite never touches an :http target's data, any more than it
   ;; starts or stops it.
   "/reset"     (fn [store _req] (reset! store {}))
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
  "A store that behaves, and one with defects for the checkers to catch."
  {:correct {:cas      cas!
             :append   append!
             :add      add!
             :transfer transfer!}
   :broken  {:cas      broken-cas!
             :append   broken-append!
             :add      broken-add!
             :transfer broken-transfer!}})

;; ## The server itself

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
  "Starts the store and returns `{:url ..., :store ..., :stop ...}`.

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

(defn -main
  "`clojure -M:serve [broken] [port=n]`. Runs until interrupted."
  [& args]
  (let [flags   (set args)
        port    (or (some->> args
                             (keep #(second (re-matches #"port=(\d+)" %)))
                             first
                             parse-long)
                    8080)
        variant (if (flags "broken") :broken :correct)
        {:keys [url]} (server {:variant variant, :port port})]
    (println (str "HTTP KVS (" (name variant) ") listening on " url))
    (println (str "Point Lite at it:  clojure -M:run-http register url=" url))
    (println "Ctrl-C to stop.")
    @(promise)))

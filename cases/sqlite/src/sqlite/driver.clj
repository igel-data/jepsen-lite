(ns sqlite.driver
  "SQLite behind a small HTTP API, so that jepsen-lite can run it as a
   `:local-process` target and kill -9 it.

   SQLite is embedded -- a library, not a server -- so it cannot be a process
   anything else can signal. This is the thin driver that makes it one: it opens
   a pool on a database file and exposes `sqlite.db`'s operations over HTTP.
   Nothing here knows about jepsen-lite; it is a test program, and the process
   `kill -9` lands on.

   The operations themselves are in `sqlite.db`, shared with the `:in-process`
   shape. All this namespace adds is the wire, and the one translation that
   matters:

     the operation was refused, certainly    -> 409
     nobody can know whether it happened     -> 500

   Started, killed and started again by `sqlite.runner`; run it by hand with

     clojure -M:driver --port 8080 --data-dir ./jepsen-data/manual"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [sqlite.db :as db])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io IOException)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent Executors)))

(def ^:private routes
  "Path -> (fn [pool request] value). Each one only unpacks the request; the
   SQLite is all in `sqlite.db`."
  {"/register/read"  (fn [pool {:keys [key]}]         (db/register-read pool key))
   "/register/write" (fn [pool {:keys [key value]}]   (db/register-write pool key value))
   "/register/cas"   (fn [pool {:keys [key old new]}] (db/register-cas pool key old new))
   "/set/add"        (fn [pool {:keys [element]}]     (db/set-add pool element))
   "/set/read"       (fn [pool _]                     (db/set-read pool))
   "/counter/add"    (fn [pool {:keys [amount]}]      (db/counter-add pool amount))
   "/counter/read"   (fn [pool _]                     (db/counter-read pool))
   "/bank/init"      (fn [pool {:keys [balances]}]    (db/bank-init pool balances))
   "/bank/read"      (fn [pool _]                     (db/bank-read pool))
   "/bank/transfer"  (fn [pool {:keys [from to amount]}]
                       (db/bank-transfer pool from to amount))})

;; ---- the server ------------------------------------------------------------

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
  "409 for what certainly didn't happen, 500 for what might have, 200 for what
   did. That is the whole protocol, and `sqlite.client` turns it straight back
   into jepsen-lite's `:fail` / `:info` / `:ok`."
  [pool]
  (reify HttpHandler
    (handle [_this exchange]
      (with-open [^HttpExchange ex exchange]
        (try
          (let [path (.getPath (.getRequestURI ex))
                op   (get routes path)]
            (cond
              (not= "POST" (.getRequestMethod ex))
              (respond! ex 405 {:error (str "use POST for " path)})

              (nil? op)
              (respond! ex 404 {:error (str "no such operation " path)})

              :else
              (let [result (try
                             {:value (op pool (request-body ex))}
                             (catch Throwable t
                               (if-let [r (db/rejection t)]
                                 {:rejected r}
                                 (throw t))))]
                (if (contains? result :rejected)
                  (respond! ex 409 {:error (:rejected result)})
                  (respond! ex 200 result)))))
          (catch Throwable t
            ;; Never leave a client waiting on a response that isn't coming: a
            ;; request that hangs would be recorded as indeterminate for a
            ;; reason that has nothing to do with SQLite.
            (try
              (respond! ex 500 {:error (str (.getName (class t)) ": "
                                            (ex-message t))})
              (catch IOException _ nil))))))))

(defn serve
  "Starts the HTTP API over an open pool. Returns `{:url ..., :stop ...}`."
  ([pool port] (serve pool port "127.0.0.1"))
  ([pool port host]
   (let [executor (Executors/newFixedThreadPool 16)
         server   (HttpServer/create
                   (InetSocketAddress. ^String host (int port)) 0)]
     ;; Without an executor every request is served on the one dispatcher
     ;; thread, and a store that answers one client at a time cannot be caught
     ;; out by a workload -- least of all bank, whose whole question is what
     ;; concurrent clients see.
     (.setExecutor server executor)
     (.createContext server "/" (exchange-handler pool))
     (.start server)
     {:url  (str "http://" host ":" (.getPort (.getAddress server)))
      :stop (fn [] (.stop server 0) (.shutdownNow executor) nil)})))

(defn -main
  "`--port n --data-dir path [--journal wal|delete] [--sync full|normal|off]`.
   Runs until it is stopped -- or killed."
  [& args]
  (let [opts     (into {} (map (fn [[k v]] [(str/replace k #"^--" "") v]))
                       (partition 2 args))
        data-dir (get opts "data-dir" "./jepsen-data/driver")
        db-file  (str data-dir "/sqlite.db")
        pool     (db/open-pool! db-file
                               {:journal     (get opts "journal" "WAL")
                                :synchronous (get opts "sync" "FULL")})
        {:keys [url stop]} (serve pool (parse-long (get opts "port" "0")))]
    ;; A clean stop closes the connections properly. A `kill -9` doesn't get one
    ;; -- nothing is flushed, no checkpoint runs, and whether the acknowledged
    ;; writes are still there is exactly the question being asked.
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (stop) (db/close-pool! pool))))
    (println (str "sqlite listening on " url " (database at " db-file ")"))
    (flush)
    @(promise)))

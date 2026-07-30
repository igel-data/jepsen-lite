(ns lmdb.driver
  "LMDB behind a small HTTP API, so that jepsen-lite can run it as a
   `:local-process` target and kill -9 it.

   LMDB is embedded -- a library, not a server -- so it cannot be a process
   anything else can signal. This is the thin driver that makes it one: it opens
   an environment on a directory and exposes `lmdb.db`'s operations over HTTP.
   Nothing here knows about jepsen-lite; it is a test program, and the process
   `kill -9` lands on.

   The operations themselves are in `lmdb.db`, shared with the `:in-process`
   shape. All this namespace adds is the wire, and the one translation that
   matters:

     the operation was refused, certainly    -> 409
     nobody can know whether it happened     -> 500

   Started, killed and started again by `lmdb.runner`; run it by hand with

     clojure -M:driver --port 8080 --data-dir ./jepsen-data/manual"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [lmdb.db :as db])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io IOException)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent Executors)))

(def ^:private routes
  "Path -> (fn [store request] value). Each one only unpacks the request; the
   LMDB is all in `lmdb.db`."
  {"/register/read"  (fn [s {:keys [key]}]         (db/register-read s key))
   "/register/write" (fn [s {:keys [key value]}]   (db/register-write s key value))
   "/register/cas"   (fn [s {:keys [key old new]}] (db/register-cas s key old new))
   "/set/add"        (fn [s {:keys [element]}]     (db/set-add s element))
   "/set/read"       (fn [s _]                     (db/set-read s))
   "/counter/add"    (fn [s {:keys [amount]}]      (db/counter-add s amount))
   "/counter/read"   (fn [s _]                     (db/counter-read s))
   "/bank/init"      (fn [s {:keys [balances]}]    (db/bank-init s balances))
   "/bank/read"      (fn [s _]                     (db/bank-read s))
   "/bank/transfer"  (fn [s {:keys [from to amount]}]
                       (db/bank-transfer s from to amount))})

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
   did. That is the whole protocol, and `lmdb.client` turns it straight back
   into jepsen-lite's `:fail` / `:info` / `:ok`."
  [store]
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
                             {:value (op store (request-body ex))}
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
            ;; reason that has nothing to do with LMDB.
            (try
              (respond! ex 500 {:error (str (.getName (class t)) ": "
                                            (ex-message t))})
              (catch IOException _ nil))))))))

(defn serve
  "Starts the HTTP API over an open environment. Returns `{:url ..., :stop ...}`."
  ([store port] (serve store port "127.0.0.1"))
  ([store port host]
   (let [executor (Executors/newFixedThreadPool 16)
         server   (HttpServer/create
                   (InetSocketAddress. ^String host (int port)) 0)]
     ;; Without an executor every request is served on the one dispatcher
     ;; thread, and a store that answers one client at a time cannot be caught
     ;; out by a workload -- least of all bank, whose whole question is what
     ;; concurrent clients see. LMDB serializes the writers itself, which is the
     ;; point; it should be LMDB doing it and not the web server.
     (.setExecutor server executor)
     (.createContext server "/" (exchange-handler store))
     (.start server)
     {:url  (str "http://" host ":" (.getPort (.getAddress server)))
      :stop (fn [] (.stop server 0) (.shutdownNow executor) nil)})))

(defn -main
  "`--port n --data-dir path [--sync on|off]`. Runs until it is stopped -- or
   killed."
  [& args]
  (let [opts     (into {} (map (fn [[k v]] [(str/replace k #"^--" "") v]))
                       (partition 2 args))
        data-dir (get opts "data-dir" "./jepsen-data/driver")
        store    (db/open-env! data-dir
                               {:sync? (not= "off" (get opts "sync" "on"))})
        {:keys [url stop]} (serve store (parse-long (get opts "port" "0")))]
    ;; A clean stop closes the environment properly. A `kill -9` doesn't get one
    ;; -- and for LMDB that is a smaller difference than it would be elsewhere,
    ;; because there is nothing to flush: whether the acknowledged writes are
    ;; still there comes down to the meta page, which is exactly the question
    ;; being asked.
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (stop) (db/close-env! store))))
    (println (str "lmdb listening on " url " (environment at " data-dir ")"))
    (flush)
    @(promise)))

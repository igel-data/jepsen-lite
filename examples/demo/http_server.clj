(ns demo.http-server
  "A minimal, persistent HTTP key-value store: the *target* for the `:http` and
   `:local-process` demos.

   This is not Jepsen Lite, and it does not require Jepsen Lite. It is a
   program, the way the database you actually wanted to test is a program. Who
   runs it is the whole of the difference between the two target-types that use
   it:

     :http           you start it, in another terminal. Lite connects, and can
                     do nothing else to it -- so it has no faults.
     :local-process  Lite starts it, and can kill -9 or SIGSTOP it, because it
                     is holding the handle.

   Run it yourself with:

     clojure -M:serve                          # a store that behaves
     clojure -M:serve broken                   # one with defects, to catch
     clojure -M:serve port=9090 dir=/tmp/kvs   # persistent across restarts

   The API is the smallest one the four workloads need. It speaks EDN so that a
   value a workload wrote comes back as the value it wrote:

     POST /read       {:key k}                    -> {:value v}
     POST /read-all   {}                          -> {:value {k v, ...}}
     POST /read-collection {:key k}               -> {:value [e, ...]}
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
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io BufferedWriter FileOutputStream IOException
                    OutputStreamWriter)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent Executors)))

;; ## The store's operations
;;
;; Each is a pure state transition -- `(fn [state request] [state' value])` --
;; which is what lets the server write a mutation down before it lets anyone
;; see it. The server runs mutations one at a time, so every one of these is
;; atomic, including the multi-key transfer that `:bank` exists to test.

(defrecord Rejected [reason])

(defn rejected
  "The store refusing an operation: a CAS that didn't match, a transfer with no
   funds, a transaction that lost a conflict. Answered with 409, and certain --
   the operation did not happen. Public because the IgelDB driver next door
   serves the same API and needs to say the same thing."
  [reason]
  (->Rejected reason))

(defn- reject
  [state reason]
  [state (rejected reason)])

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
  "The defect: silently drops every fifth element, and acknowledges it anyway,
   so the client is promised an element the store never kept."
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
  "Debits and credits in one step -- the one thing `:bank` asks of a store, and
   the reason this is an endpoint of its own rather than two calls from the
   client."
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
   ;; Reading back what /append built. Separate from /read because a growing
   ;; collection is a different shape from a value, and a store may well keep
   ;; it differently -- the IgelDB driver gives each element a key of its own.
   "/read-collection" (fn [state {:keys [key]}] [state (get state key [])])
   ;; One long-running store serves every workload and every run, so there has
   ;; to be some way to empty it. Note whose call that is: the client's, before
   ;; a run -- Lite never touches an :http target's data, any more than it
   ;; starts or stops it.
   "/reset"     (fn [_state _req] [{} {}])
   "/write"     (fn [state {:keys [key value]}] [(assoc state key value) value])
   "/write-all" (fn [state {:keys [values]}] [(merge state values) values])
   "/cas"       cas
   "/append"    append
   "/add"       add
   "/transfer"  transfer})

(def variants
  "A store that behaves, and one with defects for the checkers to catch."
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
;;              on disk. Survives both a kill -9 and a power-off.
;;   :no-fsync  flush to the operating system, but never fsync. Survives a
;;              kill -9 -- the kernel writes the page cache back regardless,
;;              which is why SIGKILL cannot test fsync -- and is lost to a
;;              power-off, which drops what was never synced.
;;   :buffered  acknowledge first, and let the writer's own buffer flush when
;;              it feels like it. The buffer dies with the process, so even a
;;              kill -9 catches this one.

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
  ;; Handing the bytes to the OS is not the same as putting them on the disk,
  ;; and the difference is invisible until the power goes.
  (when (contains? #{:durable :no-fsync} durability)
    (.flush writer))
  (when (= :durable durability)
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
  "Runs one operation: write down what it changed, then publish it.

   Mutations are serialized, which is what makes them atomic -- a `:bank` read
   never catches a transfer half-done. Reads take the atom's value and need no
   lock, so they stay concurrent with each other and with writes."
  [{:keys [state log write-lock]} path op request]
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
          (when log
            (if (= "/reset" path)
              ;; The one operation that unwrites history rather than adding to
              ;; it. Flush before truncating, or the buffer would write itself
              ;; back out afterwards.
              (do (.flush ^BufferedWriter (:writer log))
                  (.truncate (.getChannel ^FileOutputStream (:stream log)) 0))
              (log-append! log changed)))
          (reset! state state')
          value)))))

(defn- exchange-handler
  [paths]
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
              (let [result (op (request-body ex))]
                (if (instance? Rejected result)
                  (respond! ex 409 {:error (:reason result)})
                  (respond! ex 200 {:value result})))))
          (catch Throwable t
            ;; Never leave a client waiting on a response that isn't coming.
            (try
              (respond! ex 500 {:error (str (.getName (class t)) ": "
                                            (ex-message t))})
              (catch IOException _ nil))))))))

(defn serve
  "Starts an HTTP server for `handlers`: path -> (fn [request] value), where a
   value of `(rejected reason)` becomes a 409. Returns `{:url ..., :stop ...}`.

   The wire protocol, and nothing else. What is behind the endpoints -- a map
   in memory here, IgelDB in `demo.igel-driver` -- is the backend's business,
   and neither Lite nor the workloads ever find out which they are talking to."
  ([port handlers] (serve port "127.0.0.1" handlers))
  ([port host handlers]
   (let [pool   (Executors/newCachedThreadPool)
         server (HttpServer/create (InetSocketAddress. ^String host (int port)) 0)]
    ;; Without an executor every request is served on the one dispatcher
    ;; thread, and a store that answers one client at a time can't be caught
    ;; out by a workload -- least of all `:bank`, whose whole question is what
    ;; concurrent clients see.
     (.setExecutor server pool)
     (.createContext server "/" (exchange-handler handlers))
     (.start server)
     ;; The url a *client* would use. A server bound to 0.0.0.0 is reached at
     ;; an address, not at all of them.
     {:url  (str "http://" (if (= "0.0.0.0" host) "127.0.0.1" host)
                 ":" (.getPort (.getAddress server)))
      :stop (fn [] (.stop server 0) (.shutdownNow pool) nil)})))

(defn server
  "Starts the store and returns `{:url ..., :store ..., :stop ...}`.

   Options:

     :variant     :correct (default) or :broken
     :port        0 (default) for any free one
     :host        what to bind: 127.0.0.1 (default), so a demo store isn't
                  offered to the network by accident. In a container it has to
                  be 0.0.0.0 -- a published port maps to the container's own
                  interface, and a server on the container's loopback is
                  reachable only from inside it
     :data-dir    where to keep the log; omitted means the store is in memory
                  only and a restart comes back empty
     :durability  :durable (default) or :buffered -- see Persistence, above"
  ([] (server {}))
  ([{:keys [variant port host data-dir durability]
     :or   {variant :correct, port 0, host "127.0.0.1", durability :durable}}]
   (let [log   (when data-dir (open-log! data-dir durability))
         ;; Recovery: whatever the log says was committed. Note what is absent
         ;; -- any wiping or seeding. A store that reset itself on startup
         ;; would pass a crash test by forgetting the question.
         state (atom (if log (replay (:file log)) {}))
         store {:state state, :log log, :write-lock (Object.)}
         {:keys [url stop]}
         (serve port host
                (into {} (map (fn [[path op]]
                                [path (fn [request]
                                        (apply-op! store path op request))]))
                      (routes (get variants variant))))]
     {:url   url
      :store state
      :stop  (fn []
               (stop)
               (when log (.close ^BufferedWriter (:writer log)))
               nil)})))

(defn- parse-args
  "Two callers, two spellings: a person typing `broken port=9090`, and a
   program building `--variant broken --port 9090`. Both land in the same map."
  [args]
  (loop [args args, settings {}, flags #{}]
    (if-let [arg (first args)]
      (cond
        (str/starts-with? arg "--")
        (recur (drop 2 args) (assoc settings (subs arg 2) (second args)) flags)

        (str/includes? arg "=")
        (let [[k v] (str/split arg #"=" 2)]
          (recur (rest args) (assoc settings k v) flags))

        :else
        (recur (rest args) settings (conj flags arg)))
      [settings flags])))

(defn -main
  "`clojure -M:serve [broken] [buffered] [port=n] [dir=path]`, running until
   interrupted -- or, as a `:local-process` target, started and killed by Lite:

     --port n --data-dir path --variant broken --durability buffered"
  [& args]
  (let [[settings flags] (parse-args args)
        variant  (if (or (flags "broken") (= "broken" (settings "variant")))
                   :broken :correct)
        durability (cond
                     (or (flags "buffered")
                         (= "buffered" (settings "durability")))  :buffered
                     (or (flags "nofsync")
                         (= "no-fsync" (settings "durability")))  :no-fsync
                     :else                                        :durable)
        {:keys [url]}
        (server {:port       (parse-long (or (settings "port") "8080"))
                 :host       (or (settings "host") "127.0.0.1")
                 :data-dir   (or (settings "dir") (settings "data-dir"))
                 :variant    variant
                 :durability durability})]
    ;; Both of them, because a run's meaning depends on the durability as much
    ;; as on the variant -- and a silently-ignored flag is how a power-off test
    ;; ends up quietly measuring a store that fsyncs everything.
    (println (str "kvs (" (name variant) ", " (name durability)
                  ") listening on " url))
    (println (str "Point Lite at it:  clojure -M:run-http register url=" url))
    (flush)
    @(promise)))

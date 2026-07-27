(ns lite.target.http
  "The `:http` target-type: the target is already running, outside Lite, and
   Lite connects to it over HTTP. That is the whole of the relationship.

   Lite does not start, stop, restart or kill an `:http` target -- it doesn't
   own the process, and may not even be on the same machine as it. So there is
   no fault it can inject, which is why `lite.nemesis/validity` marks every
   intent ✗ for this row. What remains of the deployment axis is the
   connection, and that differs from `:in-process` in the way the `Connection`
   protocol anticipated: an in-process instance is one object every worker
   shares, while an external target hands each worker its own client.

   Nothing here knows what the target's HTTP API looks like. Speaking the
   target's wire protocol is the ClientAdapter's job -- the other axis -- and
   the adapter carries whatever endpoint its handler needs. The `:url` here is
   a deployment fact, not a protocol one: where the target is supposed to be
   listening, so that Lite can say so plainly when nothing is.

   Config:

     {:type :http
      :url  \"http://localhost:8080\"  ; where the target is listening
      :connect-timeout 2000}          ; ms to wait for it; optional"
  (:require [lite.client :as client]
            [lite.target :as target])
  (:import (java.io IOException)
           (java.net InetSocketAddress Socket URI URISyntaxException)))

(def ^:private default-connect-timeout
  "How long to wait for the target to accept a connection, in ms."
  2000)

(defn- endpoint
  "Where the target is listening, from its URL. Parsing this up front is how a
   typo in a config becomes a sentence rather than a stack trace out of a
   worker thread ten seconds into a run."
  [url]
  (when-not (string? url)
    (throw (ex-info (str "An :http target needs a :url saying where the "
                         "already-running target is listening.\n\n"
                         "  fix: give the target one, e.g.\n\n"
                         "    :target {:type :http, :url \"http://localhost:8080\"}")
                    {:lite/error  :invalid-target
                     :target-type :http
                     :url         url})))
  (let [^URI uri (try (URI. url) (catch URISyntaxException _ nil))
        host (some-> uri .getHost)
        port (some-> uri .getPort)
        port (when port
               (if (neg? port)
                 (case (.getScheme uri) "http" 80, "https" 443, nil)
                 port))]
    (when-not (and host port)
      (throw (ex-info (str (pr-str url) " isn't a URL Lite can connect to.\n\n"
                           "  fix: give :url a scheme, a host and a port, e.g."
                           " \"http://localhost:8080\".")
                      {:lite/error  :invalid-target
                       :target-type :http
                       :url         url})))
    (InetSocketAddress. ^String host (int port))))

(defn- verify-reachable!
  "Is anything actually listening there? A connect is the most this target-type
   can honestly do -- it knows the target's address but not its API, so it has
   no health endpoint to ask and no business inventing one."
  [^InetSocketAddress address url timeout]
  (try
    (with-open [socket (Socket.)]
      (.connect socket address (int timeout)))
    (catch IOException e
      (throw (ex-info (str "Nothing is listening at " url ".\n\n"
                           "  why: an :http target is one Lite connects to but"
                           " does not run. Lite never starts, stops or restarts"
                           " it, so it can't bring this one up for you.\n\n"
                           "  fix: start the target yourself and check it is"
                           " serving at " url ", or run it under a target-type"
                           " that owns the target's lifecycle, such as"
                           " :in-process.")
                      {:lite/error  :target-unreachable
                       :target-type :http
                       :url         url}
                      e)))))

(defrecord Http [adapter url address connect-timeout conns]
  target/Connection
  (acquire! [this]
    ;; Each worker gets a connection of its own to the one shared, external
    ;; target -- the way real clients do, and unlike :in-process, where there
    ;; is a single instance to hand round. Jepsen runs a worker's
    ;; open!/invoke!/close! on one thread, so the thread is the worker's name
    ;; here. Opening is a side effect, so it stays outside swap!'s retryable
    ;; function; no other thread touches this key, so there is no race to lose.
    (verify-reachable! address url connect-timeout)
    (let [worker (Thread/currentThread)]
      (when-not (contains? @conns worker)
        (let [conn (client/open adapter)]
          (swap! conns assoc worker conn))))
    this)

  (current [_this]
    (get @conns (Thread/currentThread)))

  (release! [_this]
    ;; Only this worker's connection. There is no instance to take down with
    ;; it: the target outlives the whole run, and outlived its start.
    (let [worker     (Thread/currentThread)
          [before _] (swap-vals! conns dissoc worker)]
      (when-let [conn (get before worker)]
        (client/close adapter conn)))
    nil))

(defn connection-count
  "How many worker connections are open right now."
  [target]
  (count @(:conns target)))

(defmethod target/build :http [target adapter]
  (let [{:keys [url connect-timeout]} target
        address (endpoint url)
        timeout (or connect-timeout default-connect-timeout)]
    ;; Check once, here, rather than leaving it to the first op: a target that
    ;; was never up produces a history of nothing but failures, and a history
    ;; of nothing but failures is one most checkers will happily call valid.
    ;; A run that can't mean anything shouldn't start.
    (verify-reachable! address url timeout)
    (map->Http {:adapter         adapter
                :url             url
                :address         address
                :connect-timeout timeout
                :conns           (atom {})})))

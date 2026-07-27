(ns lite.target.endpoint
  "Where a target listens, and whether anything is there yet.

   Shared by the target-types that reach the target over a socket -- `:http`,
   which connects to one somebody else started, and `:local-process`, which
   waits for one it started itself. Deliberately protocol-blind: a TCP connect
   is the most a *deployment* axis can honestly ask, since it knows the
   target's address but not its API, and has no business inventing a health
   endpoint the target may not have."
  (:import (java.io IOException)
           (java.net InetSocketAddress Socket URI URISyntaxException)))

(defn address
  "Where the target is listening, from its URL. Parsing this up front is how a
   typo in a config becomes a sentence rather than a stack trace out of a
   worker thread ten seconds into a run. `target-type` only shapes the message."
  ^InetSocketAddress [target-type url]
  (when-not (string? url)
    (throw (ex-info (str "A " target-type " target needs a :url saying where "
                         "the target listens.\n\n"
                         "  fix: give the target one, e.g.\n\n"
                         "    :target {:type " target-type
                         ", :url \"http://localhost:8080\"}")
                    {:lite/error  :invalid-target
                     :target-type target-type
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
                       :target-type target-type
                       :url         url})))
    (InetSocketAddress. ^String host (int port))))

(defn reachable?
  "Is anything accepting connections there right now?"
  [^InetSocketAddress address timeout]
  (try
    (with-open [socket (Socket.)]
      (.connect socket address (int timeout))
      true)
    (catch IOException _ false)))

(defn wait-until-reachable
  "Polls until something is listening, or `deadline-ms` of wall-clock has
   passed. Returns true if it came up. For a target Lite just started: ops
   issued before it can serve would fail for a reason that has nothing to do
   with the target under test."
  [^InetSocketAddress address deadline-ms]
  (let [deadline (+ (System/currentTimeMillis) (long deadline-ms))]
    (loop []
      (cond
        (reachable? address 200)                      true
        (< deadline (System/currentTimeMillis))        false
        :else (do (Thread/sleep 50) (recur))))))

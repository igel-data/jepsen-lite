(ns lite.target.compose
  "The `:compose` target-type: the target runs as docker-compose services that
   Lite brings up and takes down, with faults injected into the containers by
   Pumba.

   This is the only target-type that can partition anything, and the reason is
   the same one that runs through the whole axis: a fault is possible when Lite
   owns the thing the fault happens to. `:in-process` owns an object, so it can
   destroy it. `:local-process` owns a process, so the kernel will kill or stop
   it. `:compose` owns a container -- and a container has a network interface of
   its own, which is what makes cutting it off meaningful. Lite cannot partition
   a process that reaches it over loopback, and it never pretends otherwise.

   Faults are Pumba's, not Lite's:

     crash      pumba kill --signal SIGKILL, then the service is brought back up
     pause      pumba pause --duration d      (freezes the container's processes)
     partition  pumba netem loss 100%         (the container's traffic vanishes)

   `pause` and `partition` heal themselves when their duration expires, so each
   is a single op rather than a start/stop pair. `crash` doesn't: something has
   to start the container again, and that something is Lite.

   Config:

     {:type :compose
      :file      \"examples/compose/docker-compose.yml\"
      :service   \"kvs\"                      ; which service to run and hit
      :container \"jepsen-lite-kvs\"          ; its container_name, for Pumba
      :url       \"http://127.0.0.1:8080\"    ; its published endpoint
      :ready-timeout  120000                  ; ms to wait for it to listen
      :fault-duration \"5s\"                  ; how long pause/partition last
      :pumba-image \"ghcr.io/alexei-led/pumba\"
      :tc-image    \"ghcr.io/alexei-led/pumba-alpine-nettools:latest\"}

   Pumba runs as a container itself, with the docker socket mounted, so there
   is nothing to install beyond Docker. `netem` needs `tc`, which the target's
   own image is unlikely to have; `--tc-image` lends it one, which is why it is
   configured rather than assumed."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.logging :refer [info]]
            [lite.client :as client]
            [lite.target :as target]
            [lite.target.endpoint :as endpoint]))

(def ^:private defaults
  {:ready-timeout  120000
   :fault-duration "5s"
   :pumba-image    "ghcr.io/alexei-led/pumba"
   :tc-image       "ghcr.io/alexei-led/pumba-alpine-nettools:latest"})

;; ## The commands
;;
;; Kept as pure functions of the config, and public, because a command line is
;; the whole of what this target-type does: everything below just runs one and
;; reads the exit status. They are worth being able to read, and to test,
;; without Docker anywhere near.

(defn compose-command
  "`docker compose -f <file> <args...>`, run in the compose file's project."
  [{:keys [file]} & args]
  (into (cond-> ["docker" "compose"]
          file (conj "-f" file))
        args))

(defn up-command
  "`--wait`, because a published port answers before the service does: Docker
   binds the host side of the mapping as soon as the container is created, so a
   connection succeeds -- and is closed again immediately -- while the process
   inside is still starting. Ops issued into that window fail for a reason that
   has nothing to do with the target.

   `--wait` hands the question to the service's own `healthcheck:`, which is
   where it belongs: only the service knows what ready means for it. Without
   one, Docker waits for the container to be running and no more, and Lite's
   endpoint probe is all that stands between the run and that window."
  [{:keys [service] :as config}]
  (apply compose-command config
         (cond-> ["up" "-d" "--wait"] service (conj service))))

(defn down-command
  "`down -v`: the volumes go too, so the next run starts from an empty store
   rather than inheriting the last one's data."
  [config]
  (compose-command config "down" "-v"))

(defn pumba-command
  "Pumba, as a container with the docker socket mounted, so nothing has to be
   installed for it. `args` is the fault and its options."
  [{:keys [pumba-image]} & args]
  (into ["docker" "run" "--rm"
         "-v" "/var/run/docker.sock:/var/run/docker.sock"
         (or pumba-image (:pumba-image defaults))]
        args))

(defn kill-command
  [{:keys [container] :as config}]
  (pumba-command config "kill" "--signal" "SIGKILL" container))

(defn pause-command
  [{:keys [container fault-duration] :as config}]
  (pumba-command config "pause" "--duration"
                 (or fault-duration (:fault-duration defaults))
                 container))

(defn partition-command
  "100% egress loss for the duration: the container is still running and still
   accepting requests, and nothing it says gets out. Ops in the window time out,
   which is indeterminate and not failure -- the target may well have applied
   them."
  [{:keys [container fault-duration tc-image] :as config}]
  (pumba-command config
                 "netem" "--duration" (or fault-duration
                                          (:fault-duration defaults))
                 "--tc-image" (or tc-image (:tc-image defaults))
                 "loss" "--percent" "100"
                 container))

;; ## Running them

(defn- sh!
  "Runs a command, and explains itself if it fails. Docker's own message is the
   useful part, so it goes in whole."
  [command what]
  (info what (str/join " " command))
  (let [{:keys [exit out err]} (apply shell/sh command)]
    (when-not (zero? exit)
      (throw (ex-info (str what " failed (status " exit ").\n\n"
                           "  ran: " (str/join " " command) "\n\n"
                           "  it said: " (str/trim (str (not-empty err) out)))
                      {:lite/error  :compose-command-failed
                       :target-type :compose
                       :command     command
                       :exit        exit})))
    out))

(defn- wait-ready!
  [{:keys [address url ready-timeout] :as config} what]
  (when address
    (when-not (endpoint/wait-until-reachable
               address (or ready-timeout (:ready-timeout defaults)))
      (throw (ex-info (str "The target never started listening at " url
                           " (waited " (or ready-timeout
                                           (:ready-timeout defaults))
                           "ms) after " what ".\n\n"
                           "  fix: check that the service publishes that port,"
                           " and `docker compose -f " (:file config)
                           " logs` for what it did instead. Give the service a"
                           " `healthcheck:` too: a published port accepts"
                           " connections before the process behind it is"
                           " listening, so a healthcheck is the only reliable"
                           " way to say when the target is ready.")
                      {:lite/error  :target-start-failed
                       :target-type :compose
                       :url         url})))))

(defrecord Compose [adapter config conn crashes lifecycle-lock]
  target/Lifecycle
  (start! [_this]
    (locking lifecycle-lock
      (sh! (up-command config) "Bringing the compose target up:")
      (wait-ready! config "starting it")))

  (stop! [_this]
    (locking lifecycle-lock
      (when-let [c (first (reset-vals! conn nil))]
        (client/close adapter c))
      (sh! (down-command config) "Taking the compose target down:")
      nil))

  target/Connection
  (acquire! [this]
    ;; One connection, shared: there is one target, and Lite owns it. A crash
    ;; replaces the connection, which is why it lives in an atom.
    (locking lifecycle-lock
      (when-not @conn
        (reset! conn (client/open adapter))))
    this)

  (current [_this]
    @conn)

  (release! [_this]
    nil))

(defn crash!
  "SIGKILL inside the container, then bring the service back.

   Pumba does the killing; the restart is Lite's, because a container that is
   gone stays gone unless something starts it -- a `restart:` policy in the
   compose file would race with this, so `up -d` is issued either way and is a
   no-op if Docker got there first. As with `:local-process`, the new
   connection is published before the old one is closed, so ops in the window
   meet a dead target rather than a nil one."
  [{:keys [adapter config conn crashes lifecycle-lock]}]
  (locking lifecycle-lock
    (sh! (kill-command config) "Killing the target container:")
    (sh! (up-command config) "Starting it again:")
    (wait-ready! config "restarting it")
    (let [fresh     (client/open adapter)
          [stale _] (reset-vals! conn fresh)]
      ;; Off this thread: closing a connection to the container that just died
      ;; waits for its in-flight requests to time out, and the fault shouldn't
      ;; be charged for that.
      (when stale
        (doto (Thread. ^Runnable #(client/close adapter stale))
          (.setDaemon true)
          (.start))))
    (let [n (swap! crashes inc)]
      (info "Killed the target container and started it again"
            (str "(" n " so far)"))
      n)))

(defn pause!
  "Freezes every process in the container for the fault's duration, after which
   Pumba unfreezes it. Returns the duration in seconds -- a number, because
   checkers read every op in the history, nemesis ops included."
  [{:keys [config]}]
  (sh! (pause-command config) "Pausing the target container:")
  0)

(defn partition!
  "Drops everything the container sends, for the fault's duration. Requests
   still arrive, so an op in the window may well take effect with nobody left
   to tell -- which is what `:info` means."
  [{:keys [config]}]
  (sh! (partition-command config) "Cutting the target container off:")
  0)

(defn crash-count
  [target]
  @(:crashes target))

(defn- validate-config!
  [{:keys [file container]}]
  (when-not (string? file)
    (throw (ex-info (str "A :compose target needs a :file: the docker-compose"
                         " file that defines it.\n\n"
                         "  fix: e.g.\n\n"
                         "    :target {:type :compose\n"
                         "             :file      \"docker-compose.yml\"\n"
                         "             :service   \"kvs\"\n"
                         "             :container \"jepsen-lite-kvs\"\n"
                         "             :url       \"http://127.0.0.1:8080\"}")
                    {:lite/error  :invalid-target
                     :target-type :compose
                     :file        file})))
  (when-not (string? container)
    (throw (ex-info (str "A :compose target needs a :container: the name Pumba"
                         " should aim its faults at.\n\n"
                         "  why: compose names containers after the project and"
                         " the service unless you say otherwise, and a fault"
                         " injector that guessed wrong would perturb the wrong"
                         " thing -- or nothing.\n\n"
                         "  fix: give the service a `container_name:` in the"
                         " compose file, and name it here too.")
                    {:lite/error  :invalid-target
                     :target-type :compose
                     :container   container}))))

(defmethod target/build :compose [target adapter]
  (validate-config! target)
  (map->Compose
   {:adapter        adapter
    :config         (assoc (merge defaults
                                  (select-keys target
                                               [:file :service :container :url
                                                :ready-timeout :fault-duration
                                                :pumba-image :tc-image]))
                           :address (when (:url target)
                                      (endpoint/address :compose (:url target))))
    :conn           (atom nil)
    :crashes        (atom 0)
    :lifecycle-lock (Object.)}))

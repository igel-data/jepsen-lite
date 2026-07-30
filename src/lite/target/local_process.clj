(ns lite.target.local-process
  "The `:local-process` target-type: the target runs as a separate OS process
   on this machine, and Lite runs it -- starts it, holds its handle, and can
   signal it.

   This is the first target-type where Lite owns something the operating system
   will kill for it, and that is the whole point of it. `:in-process` can
   simulate a crash by destroying an object and building another, which
   exercises a target's recovery code but not the process boundary; `:http`
   can't crash anything at all. Here `crash` is `SIGKILL` -- a real `kill -9`,
   with no chance to flush, close files or run shutdown hooks -- followed by a
   real restart that has to find its data on disk. `pause` is `SIGSTOP` and
   `SIGCONT`: the process is still there, still holding its port, and simply
   stops answering.

   What it does *not* test: loss of writes that reached the OS but were never
   fsynced. SIGKILL kills the process, not the kernel, so the page cache is
   still written back. It tests process death, restart and recovery -- real and
   worth testing -- and it catches data buffered in the *target's own* memory.
   True power-loss testing needs a fault injector below the filesystem.

   Config:

     {:type :local-process
      :command [\"java\" \"-cp\" ... \"-m\" \"my.driver\" \"--port\" \"8080\"]
      :url     \"http://127.0.0.1:8080\"  ; how to tell it has come up
      :dir     \"/tmp/target\"            ; working directory; optional
      :env     {\"FOO\" \"bar\"}          ; extra environment; optional
      :log     \"/tmp/target.log\"        ; where its output goes; optional
      :ready-timeout 30000}              ; ms to wait for it to listen

   `:command` must be the target's own process, not a shell that launches it:
   Lite signals the process it started, and a shell wrapper would take the
   signal instead of the target."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [lite.client :as client]
            [lite.lazyfs :as lazyfs]
            [lite.target :as target]
            [lite.target.endpoint :as endpoint])
  (:import (java.io File)
           (java.lang ProcessBuilder$Redirect)
           (java.util.concurrent TimeUnit)))

(def ^:private default-ready-timeout
  "How long to wait for a started target to accept connections, in ms."
  30000)

(defn- log-tail
  "The last few lines of the target's output, for an error message. A process
   that won't start has usually already said why."
  [log]
  (let [file (io/file log)]
    (when (and file (.isFile file))
      (let [lines (take-last 15 (str/split-lines (slurp file)))]
        (when (seq lines)
          (str "\n\n  it said:\n\n"
               (str/join "\n" (map #(str "    " %) lines))))))))

(defn- launch!
  "Starts the process and returns the handle. Does not wait for it to be ready."
  [{:keys [command dir env log]}]
  (let [builder (ProcessBuilder. ^java.util.List (vec command))
        out     (if log
                  ;; Appended, not truncated: a crash restarts the process, and
                  ;; the interesting part of the log is usually what the last
                  ;; life said before it died.
                  (ProcessBuilder$Redirect/appendTo (io/file log))
                  ;; Otherwise the target's output would interleave with the
                  ;; run's own and make both unreadable.
                  ProcessBuilder$Redirect/DISCARD)]
    (when dir (.directory builder (io/file dir)))
    (when (seq env)
      (let [environment (.environment builder)]
        (doseq [[k v] env] (.put environment (str k) (str v)))))
    (.redirectOutput builder out)
    (.redirectErrorStream builder true)
    (.start builder)))

(defn- wait-ready!
  "Blocks until the target is accepting connections, or explains why it never
   did. A target that isn't listening yet would fail its first ops for a reason
   that has nothing to do with what is being tested."
  [^Process process {:keys [address url ready-timeout log command]}]
  (let [timeout (or ready-timeout default-ready-timeout)]
    (cond
      ;; Nothing to poll: a target with no endpoint (a stdio driver, say) is
      ;; taken to be ready once it is running.
      (nil? address)
      (when-not (.isAlive process)
        (throw (ex-info (str "The target exited immediately (status "
                             (.exitValue process) ").\n\n"
                             "  fix: check :command -- " (pr-str (vec command))
                             (log-tail log))
                        {:lite/error  :target-start-failed
                         :target-type :local-process
                         :command     (vec command)})))

      (endpoint/wait-until-reachable address timeout)
      nil

      :else
      (let [alive? (.isAlive process)]
        (.destroyForcibly process)
        (throw (ex-info (str "The target never started listening at " url
                             " (waited " timeout "ms).\n\n"
                             "  why: "
                             (if alive?
                               (str "the process is running but nothing is"
                                    " accepting connections there. Is :url the"
                                    " address it actually binds?")
                               (str "the process exited with status "
                                    (.exitValue process) "."))
                             "\n\n  fix: check :command -- "
                             (pr-str (vec command))
                             ", and raise :ready-timeout if it is simply slow"
                             " to start."
                             (log-tail log))
                        {:lite/error  :target-start-failed
                         :target-type :local-process
                         :url         url
                         :command     (vec command)}))))))

(defn- signal!
  "Sends a signal to the target by name -- `STOP`, `CONT`. Java can only kill a
   process it started, so the ones that stop it short of death go through
   `kill`."
  [^Process process signal]
  (let [pid    (.pid process)
        {:keys [exit err]} (shell/sh "kill" (str "-" signal) (str pid))]
    (when-not (zero? exit)
      (throw (ex-info (str "Couldn't send SIG" signal " to the target (pid "
                           pid "): " (str/trim (str err)))
                      {:lite/error  :signal-failed
                       :target-type :local-process
                       :signal      signal
                       :pid         pid})))
    pid))

(defrecord LocalProcess [adapter config lazyfs-config lazyfs process conn
                         paused crashes lifecycle-lock shutdown-hook]
  target/Lifecycle
  (start! [_this]
    (locking lifecycle-lock
      ;; The filesystem first, then the target on top of it: the process has to
      ;; open its data directory *through* lazyfs, or its writes never pass the
      ;; layer that a power-off clears.
      (when (and lazyfs-config (not @lazyfs))
        (reset! lazyfs (lazyfs/mount! lazyfs-config)))
      (when-not @process
        (let [proc (launch! config)]
          (reset! process proc)
          ;; If the run dies without unwinding -- a Ctrl-C, an OOM -- the
          ;; target must not outlive it. Lite started this process; nobody else
          ;; is going to clean it up.
          (let [hook (Thread. ^Runnable (fn [] (.destroyForcibly proc)))]
            (.addShutdownHook (Runtime/getRuntime) hook)
            (reset! shutdown-hook hook))
          (wait-ready! proc config)
          (info "Started the target" (pr-str (:command config))
                (str "(pid " (.pid proc) ")"))))))

  (stop! [_this]
    (locking lifecycle-lock
      (when-let [^Process proc @process]
        ;; A stopped process can't act on SIGTERM, so let it run first.
        (when @paused (signal! proc "CONT") (reset! paused false))
        (.destroy proc)
        (when-not (.waitFor proc 5 TimeUnit/SECONDS)
          (warn "The target ignored SIGTERM; killing it")
          (.destroyForcibly proc))
        (when-let [hook @shutdown-hook]
          ;; Removing it throws if a shutdown is already under way, which is
          ;; exactly when we don't care.
          (try (.removeShutdownHook (Runtime/getRuntime) hook)
               (catch IllegalStateException _ nil))
          (reset! shutdown-hook nil))
        (reset! process nil))
      (when-let [c (first (reset-vals! conn nil))]
        (client/close adapter c))
      ;; The filesystem goes last, after the process that was using it. A FUSE
      ;; mount left behind would outlive the run and break the next one.
      (when-let [handle (first (reset-vals! lazyfs nil))]
        (lazyfs/quiesce! handle))
      nil))

  target/Connection
  (acquire! [this]
    ;; One connection, shared by every worker, as with :in-process: there is
    ;; one target process and Lite owns it. A crash replaces the connection --
    ;; that is why it lives in an atom rather than being captured per worker.
    (locking lifecycle-lock
      (when-not @conn
        (reset! conn (client/open adapter))))
    this)

  (current [_this]
    @conn)

  (release! [_this]
    ;; A worker letting go of its client doesn't take the process down with it.
    nil))

(defn- release-later!
  "Closes a connection to the target that just died, out of the way.

   Closing one can block: a client with requests in flight to a process that no
   longer exists waits for each of them to time out first. Doing that on the
   nemesis's thread makes every crash cost a request timeout, which is how a
   five-second run ends up taking half a minute -- and the connection being
   closed is the dead one, which nobody is going to use again either way."
  [adapter conn]
  (doto (Thread. ^Runnable #(client/close adapter conn))
    (.setDaemon true)
    (.start)))

(defn- kill-and-restart!
  "SIGKILL, start it again, wait until it can serve, reconnect.

   `before-kill!` runs first, while the target is still alive, and is the only
   thing that differs between a crash and a power-off. Everything after it --
   the kill, the restart, the readiness wait, the fresh connection -- is the
   same sequence, and is shared rather than written twice.

   No chance to flush, close a file, or run a shutdown hook: whatever the
   target hadn't got onto disk is gone, and whether that costs it any
   acknowledged writes is the question the run is asking. Ops that land while
   it is down get a connection error and are recorded honestly by the wrapper:
   refused means the op certainly never happened, a timeout means nobody knows.

   The new connection is published before the old one is closed, so there is
   never a moment with no connection at all -- ops in the window should meet a
   dead target, which is the truth, rather than a nil one."
  [{:keys [adapter config process conn paused crashes lifecycle-lock]} what
   before-kill!]
  (locking lifecycle-lock
    (when before-kill! (before-kill!))
    (let [^Process old @process]
      (when old
        (.destroyForcibly old)
        (.waitFor old 10 TimeUnit/SECONDS))
      (reset! paused false)
      (let [proc (launch! config)]
        (reset! process proc)
        (wait-ready! proc config)
        (let [fresh      (client/open adapter)
              [stale _]  (reset-vals! conn fresh)]
          (when stale (release-later! adapter stale)))
        (let [n (swap! crashes inc)]
          (info what (str "(" n " so far; pid " (when old (.pid old)) " -> "
                          (.pid proc) ")"))
          n)))))

(defn crash!
  "A real crash: SIGKILL, then start it again.

   What it does *not* test is fsync. SIGKILL kills the process, not the kernel,
   so writes the target handed to the OS are written back regardless and
   survive -- which means a target that fsyncs and one that merely writes come
   through this identically. `power-off!` is the one that tells them apart."
  [target]
  (kill-and-restart! target "Killed the target and started it again" nil))

(defn power-off!
  "Power loss: throw away everything the target never fsynced, then SIGKILL,
   then start it again.

   The clear happens while the target is still running and is *awaited* before
   the kill. Doing it the other way round -- or not waiting -- would leave what
   the power-off actually dropped undefined, and an undefined fault proves
   nothing about durability.

   The restarted target re-attaches to a data directory that has lost exactly
   the writes it never made durable, and has to recover from there. A target
   that fsyncs what it acknowledges comes back whole; one that acknowledges
   first and syncs later does not, and the checker says so."
  [{:keys [lazyfs] :as target}]
  (let [handle @lazyfs]
    (when-not handle
      (throw (ex-info (str "This target has no lazyfs mount, so there is "
                           "nothing to power off.\n\n"
                           "  why: power-off drops the writes a target never "
                           "fsynced, which only lazyfs can see. Lite mounts it "
                           "for a run that asks for :power-off.")
                      {:lite/error  :power-off-unavailable
                       :target-type :local-process})))
    (kill-and-restart! target "Powered the target off and started it again"
                       #(lazyfs/clear-cache! handle))))

(defn pause!
  "SIGSTOP: the process stays alive and keeps its port, and stops answering.
   Ops that land in the window wait, and time out -- which is indeterminate,
   not failure: a paused target may well apply the request once it wakes.

   Returns the pid it stopped. Keep it a number, as with `crash!`: checkers
   read every op in the history, nemesis ops included, and some are strict
   about what an op's value may be."
  [{:keys [process paused lifecycle-lock]}]
  (locking lifecycle-lock
    (when-let [^Process proc @process]
      (when-not @paused
        (signal! proc "STOP")
        (reset! paused true)
        (info "Paused the target" (str "(pid " (.pid proc) ")")))
      (.pid proc))))

(defn resume!
  "SIGCONT: the process picks up where it left off, including any requests that
   were queued on its socket while it slept. Returns the pid it continued."
  [{:keys [process paused lifecycle-lock]}]
  (locking lifecycle-lock
    (when-let [^Process proc @process]
      (when @paused
        (signal! proc "CONT")
        (reset! paused false)
        (info "Resumed the target" (str "(pid " (.pid proc) ")")))
      (.pid proc))))

(defn crash-count
  "How many times this target has been killed and restarted."
  [target]
  @(:crashes target))

(defn pid
  "The OS process id of the target as it is running now, or nil if it isn't."
  [target]
  (some-> ^Process @(:process target) .pid))

(defn alive?
  "Is the target process running?"
  [target]
  (boolean (some-> ^Process @(:process target) .isAlive)))

(defn- validate-config!
  [{:keys [command dir]}]
  (when-not (and (sequential? command) (seq command) (every? string? command))
    (throw (ex-info (str "A :local-process target needs a :command to run:"
                         " a vector of strings, the program first.\n\n"
                         "  fix: e.g.\n\n"
                         "    :target {:type :local-process\n"
                         "             :command [\"my-server\" \"--port\" \"8080\"]\n"
                         "             :url     \"http://127.0.0.1:8080\"}\n\n"
                         "  note: give the program itself, not a shell that"
                         " launches it -- Lite signals the process it started,"
                         " and a shell would take the signal instead.")
                    {:lite/error  :invalid-target
                     :target-type :local-process
                     :command     command})))
  (when (and dir (not (.isDirectory ^File (io/file dir))))
    (throw (ex-info (str "The :dir a :local-process target should run in "
                         "doesn't exist: " (pr-str dir))
                    {:lite/error  :invalid-target
                     :target-type :local-process
                     :dir         dir}))))

(defn- validate-lazyfs-config!
  [{:keys [mount-point root] :as lazyfs-config}]
  (when lazyfs-config
    (when-not (and (string? mount-point) (string? root))
      (throw (ex-info (str "A :lazyfs mount needs a :mount-point and a :root."
                           "\n\n"
                           "  fix: e.g.\n\n"
                           "    :lazyfs {:dir         \"/opt/lazyfs/lazyfs\"\n"
                           "             :mount-point \"/tmp/target/data\"\n"
                           "             :root        \"/tmp/target/root\"}\n\n"
                           "  note: the target's own data directory has to be"
                           " the mount point, or somewhere under it. A target"
                           " writing anywhere else never passes through lazyfs,"
                           " and every power-off would then pass while dropping"
                           " nothing.")
                      {:lite/error  :invalid-target
                       :target-type :local-process
                       :lazyfs      lazyfs-config})))))

(defmethod target/verify-faults! :local-process [target intents]
  (when (some #{:power-off} intents)
    (let [lazyfs-config (:lazyfs target)]
      (when-let [reason (lazyfs/unavailable (:dir lazyfs-config))]
        (throw (ex-info (str ":power-off can't run on this host.\n\n"
                             "  why: " reason "\n\n"
                             "  fix: run in a Linux environment with lazyfs"
                             " built and /dev/fuse available -- a VM or"
                             " container such as OrbStack Ubuntu, or CI -- and"
                             " point :target {:lazyfs {:dir ...}} at the"
                             " built checkout.\n\n"
                             "  note: Lite won't quietly fall back to :crash"
                             " here. A plain crash can't drop unfsynced writes,"
                             " so it would report durability it never tested.")
                        {:lite/error  :power-off-unavailable
                         :target-type :local-process
                         :os          (System/getProperty "os.name")})))
      (when-not (:mount-point lazyfs-config)
        (throw (ex-info (str ":power-off needs the target's data directory on "
                             "a lazyfs mount, and this target has none.\n\n"
                             "  fix: add :lazyfs {:dir ..., :mount-point ...,"
                             " :root ...} to the target, and start the target"
                             " with its data directory at the mount point.")
                        {:lite/error  :power-off-unavailable
                         :target-type :local-process}))))))

(defmethod target/build :local-process [target adapter]
  (validate-config! target)
  (validate-lazyfs-config! (:lazyfs target))
  (map->LocalProcess
   {:adapter        adapter
    ;; The address is parsed now, not at start: a typo in :url should be a
    ;; sentence before the run, not a timeout in the middle of one.
    :config         (assoc (select-keys target [:command :dir :env :log :url
                                                :ready-timeout])
                           :address (when (:url target)
                                      (endpoint/address :local-process
                                                        (:url target))))
    :lazyfs-config  (:lazyfs target)
    :lazyfs         (atom nil)
    :process        (atom nil)
    :conn           (atom nil)
    :paused         (atom false)
    :crashes        (atom 0)
    :lifecycle-lock (Object.)
    :shutdown-hook  (atom nil)}))

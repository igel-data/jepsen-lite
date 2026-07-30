(ns lite.lazyfs
  "lazyfs: a FUSE filesystem that holds writes in a cache of its own until the
   target fsyncs them, and can be told to throw that cache away.

   This is what makes `:power-off` different from `:crash`. A SIGKILL kills the
   process but not the kernel, so writes the target handed to the OS are
   written back anyway and survive -- which means a plain crash cannot tell a
   target that fsyncs from one that doesn't. lazyfs moves that boundary up:
   nothing reaches the backing directory until an fsync, so clearing its cache
   drops precisely the writes the target failed to make durable, and the
   restarted target has to recover from a disk that lost them.

   Verified against lazyfs 0.3.1 (FUSE3):

     - the mount script resolves its binary relative to its own directory, so
       it has to be run from the lazyfs checkout, not from anywhere
     - clearing the cache is a line on a FIFO, and a second FIFO reports
       `finished::clear-cache` when it is done -- which is what lets a caller
       order the clear strictly before the kill
     - a file written with fsync survives the clear; one written without it
       does not. That is the whole mechanism.

   Nothing here knows about workloads, checkers, or the ClientAdapter. It is a
   filesystem, used by one target-type."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]])
  (:import (java.io File)
           (java.lang ProcessBuilder$Redirect)
           (java.util.concurrent TimeUnit)))

(def ^:private default-clear-timeout
  "How long to wait for lazyfs to report the cache cleared, in ms. Generous:
   the alternative to waiting is a kill racing the clear, and a power-off whose
   ordering is undefined tests nothing."
  30000)

;; ## Is this host able to run it at all?

(defn linux? []
  (str/starts-with? (str/lower-case (System/getProperty "os.name" "")) "linux"))

(defn- fuse-device? []
  (.exists (io/file "/dev/fuse")))

(defn- scripts-in
  "The mount/unmount scripts, if `dir` looks like a lazyfs checkout."
  [dir]
  (when dir
    (let [mount   (io/file dir "scripts/mount-lazyfs.sh")
          unmount (io/file dir "scripts/umount-lazyfs.sh")
          binary  (io/file dir "build/lazyfs")]
      (when (and (.isFile mount) (.isFile unmount) (.isFile binary))
        {:mount mount, :unmount unmount, :binary binary}))))

(defn unavailable
  "Why this host can't run lazyfs, or nil if it can. A sentence, not a boolean:
   the point of the check is to say what is missing."
  [dir]
  (cond
    (not (linux?))
    (str "lazyfs is a FUSE filesystem, and FUSE means Linux. This host is "
         (System/getProperty "os.name") ".")

    (not (fuse-device?))
    (str "there is no /dev/fuse on this host, so no FUSE filesystem can be "
         "mounted. In a container, that usually means it was started without "
         "--device /dev/fuse and the SYS_ADMIN capability.")

    (nil? dir)
    (str "Lite wasn't told where lazyfs is. Point :lazyfs {:dir ...} at a "
         "built lazyfs checkout -- the directory holding scripts/ and "
         "build/lazyfs.")

    (nil? (scripts-in dir))
    (str "no built lazyfs at " (pr-str dir) ": expected scripts/mount-lazyfs.sh"
         ", scripts/umount-lazyfs.sh and build/lazyfs under it. Build it with"
         " libs/libpcache/build.sh, then lazyfs/build.sh.")

    :else nil))

;; ## Mounting

(declare unmount!)

(defn- config-path
  "Where to put the toml, avoiding a trap in lazyfs's own argument parsing.

   It scans the token after `--config-path` for the substring `-o`, meaning to
   catch a missing value followed by a FUSE `-o` option -- and a *path* that
   happens to contain those two characters trips it too. A directory called
   `power-off-test` is enough. lazyfs then silently loads its default config
   instead, with a different fault FIFO, so every clear-cache would go nowhere
   and every power-off run would pass having dropped nothing at all.

   So Lite names this file, in a place it has checked."
  [work]
  (let [candidate (io/file work "lazyfs.toml")]
    (if-not (str/includes? (str/lower-case (.getAbsolutePath candidate)) "-o")
      candidate
      ;; The work directory's own name is unusable. Somewhere neutral, then.
      (let [fallback (io/file (System/getProperty "java.io.tmpdir")
                              (str "lazyfs" (System/nanoTime) ".toml"))]
        (when (str/includes? (str/lower-case (.getAbsolutePath fallback)) "-o")
          (throw (ex-info (str "Nowhere to put lazyfs's config: both "
                               (.getAbsolutePath candidate) " and "
                               (.getAbsolutePath fallback) " contain \"-o\", "
                               "which lazyfs mistakes for a missing config "
                               "path and quietly ignores.\n\n"
                               "  fix: run with a data directory, or a "
                               "java.io.tmpdir, whose path has no \"-o\" in it.")
                          {:lite/error :lazyfs-config-path})))
        fallback))))

(defn- write-config!
  "lazyfs is configured by a toml file. Both FIFOs are named: the second one is
   what makes the clear awaitable rather than hopeful."
  [{:keys [config fifo completed logfile cache-size]}]
  (spit config
        (str "[faults]\n"
             "fifo_path=\"" fifo "\"\n"
             "fifo_path_completed=\"" completed "\"\n"
             "[cache]\n"
             "apply_eviction=false\n"
             "[cache.simple]\n"
             "custom_size=\"" (or cache-size "512mb") "\"\n"
             "blocks_per_page=1\n"
             "[filesystem]\n"
             "log_all_operations=false\n"
             "logfile=\"" logfile "\"\n"))
  config)

(defn- mounted?
  [mount-point]
  (let [{:keys [out]} (shell/sh "mount")]
    (boolean (re-find (re-pattern (str " on " (java.util.regex.Pattern/quote
                                               (str mount-point))
                                       " type fuse.lazyfs"))
                      out))))

(defn- await-mount!
  [mount-point timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (mounted? mount-point)                   true
            (< deadline (System/currentTimeMillis))  false
            :else (do (Thread/sleep 100) (recur))))))

(defn mount!
  "Mounts lazyfs at `:mount-point`, backed by `:root`, and returns a handle.

   The target's data directory has to be *under the mount point* -- if it
   isn't, the writes never go through lazyfs, clearing the cache affects
   nothing, and every power-off run passes while proving nothing."
  [{:keys [dir mount-point root work-dir cache-size mount-timeout]
    :or   {mount-timeout 30000}}]
  (let [_       (or (scripts-in dir)
                    (throw (ex-info (str "No built lazyfs at " (pr-str dir))
                                    {:lite/error :lazyfs-unavailable
                                     :dir        dir})))
        ;; Absolute from here on. The mount script runs with the lazyfs
        ;; checkout as its working directory, so a relative path -- which is a
        ;; perfectly ordinary thing for a caller to pass -- would be resolved
        ;; against that instead of against the caller's.
        mount-point (.getAbsolutePath (io/file mount-point))
        root        (.getAbsolutePath (io/file root))
        ;; Beside the mount point, never inside it. The config, the FIFOs and
        ;; the log have to stay reachable once lazyfs is mounted, and anything
        ;; under the mount point is behind the very filesystem being set up --
        ;; a FIFO lazyfs must open in order to exist.
        work    (doto (io/file (or work-dir
                                   (.getParentFile (.getAbsoluteFile
                                                    (io/file mount-point)))))
                  (.mkdirs))
        handle  {:dir         dir
                 :mount-point (str mount-point)
                 :root        (str root)
                 :config      (str (config-path work))
                 :fifo        (str (io/file work "faults.fifo"))
                 :completed   (str (io/file work "faults-completed.fifo"))
                 :logfile     (str (io/file work "lazyfs.log"))
                 :cache-size  cache-size}]
    (doto (io/file mount-point) (.mkdirs))
    (doto (io/file root) (.mkdirs))
    (write-config! handle)
    ;; Launched with its output going to a *file*, not to pipes we then close.
    ;; The script backgrounds the lazyfs daemon, which inherits whatever stdio
    ;; the script had: give it a pipe and the daemon dies the moment the parent
    ;; stops reading -- it starts, opens its log, and is gone before it ever
    ;; mounts anything.
    ;;
    ;; The working directory is the lazyfs checkout, because the script looks
    ;; for ./build/lazyfs relative to where it is *run*; the script itself is
    ;; named absolutely, because a relative program name is resolved against
    ;; this JVM's directory rather than that one.
    (let [script  (io/file dir "scripts/mount-lazyfs.sh")
          output  (io/file work "lazyfs-mount.log")
          builder (doto (ProcessBuilder.
                         ^java.util.List [(.getAbsolutePath script)
                                          "-c" (:config handle)
                                          "-m" (:mount-point handle)
                                          "-r" (:root handle)
                                          "-s"])
                    (.directory (io/file dir))
                    (.redirectErrorStream true)
                    (.redirectOutput (ProcessBuilder$Redirect/appendTo output)))
          process (.start builder)]
      (when-not (.waitFor process 30 TimeUnit/SECONDS)
        (.destroyForcibly process))
      (when-not (zero? (.exitValue process))
        (throw (ex-info (str "Couldn't mount lazyfs at " mount-point ".\n\n"
                             "  it said: " (str/trim (slurp output)))
                        {:lite/error  :lazyfs-mount-failed
                         :mount-point (str mount-point)}))))
    (when-not (await-mount! (:mount-point handle) mount-timeout)
      (throw (ex-info (str "lazyfs never appeared at " mount-point " (waited "
                           mount-timeout "ms).\n\n"
                           "  it said: "
                           (let [output (io/file work "lazyfs-mount.log")]
                             (if (.isFile output)
                               (str/trim (slurp output))
                               "nothing at all"))
                           "\n\n  fix: check " (:logfile handle) ", and that "
                           "/etc/fuse.conf has user_allow_other.")
                      {:lite/error  :lazyfs-mount-failed
                       :mount-point (str mount-point)})))
    ;; Mounted is not the same as ours. If lazyfs ignored the config it will
    ;; have made its fault FIFO somewhere else entirely, and every clear-cache
    ;; sent to the one named here would go nowhere -- a power-off run that
    ;; drops nothing and passes. Prove the handshake exists before letting a
    ;; run depend on it.
    (when-not (.exists (io/file (:fifo handle)))
      (unmount! handle)
      (throw (ex-info (str "lazyfs mounted but isn't using Lite's config: no "
                           "fault FIFO at " (:fifo handle) ".\n\n"
                           "  why: without it there is nothing to send a "
                           "clear-cache to, so a power-off would quietly drop "
                           "nothing and every run would pass.\n\n"
                           "  fix: check " (:logfile handle) " -- lazyfs logs "
                           "which config it loaded.")
                      {:lite/error :lazyfs-config-ignored
                       :config     (:config handle)
                       :fifo       (:fifo handle)})))
    (info "Mounted lazyfs at" (:mount-point handle) "backed by" (:root handle))
    handle))

(defn unmount!
  "Unmounts, forcibly if the ordinary way won't -- a run that killed its target
   mid-write can leave the mount wedged, and leaving a wedged FUSE mount behind
   would break the next run rather than this one."
  [{:keys [dir mount-point]}]
  (when (and dir mount-point)
    (let [{:keys [exit]} (shell/with-sh-dir dir
                           (shell/sh "./scripts/umount-lazyfs.sh"
                                     "-m" (str mount-point)))]
      (when-not (zero? exit)
        (warn "lazyfs wouldn't unmount cleanly; forcing it")
        ;; fusermount3, not fusermount: this is FUSE3.
        (shell/sh "fusermount3" "-uz" (str mount-point)))
      (info "Unmounted lazyfs at" (str mount-point))
      nil)))

;; ## The fault

(defn clear-cache!
  "Throws away everything lazyfs is holding that was never fsynced, and waits
   until it says it has.

   The wait is the whole reason the completion FIFO is configured. Without it a
   caller can only guess when the cache is gone, and a SIGKILL that arrives
   first makes the power-off mean nothing in particular."
  ([handle] (clear-cache! handle default-clear-timeout))
  ([{:keys [fifo completed]} timeout-ms]
   (let [;; Opening a FIFO for writing blocks until lazyfs is reading it, and
         ;; its completion write blocks until we read -- so the handshake can't
         ;; be missed by arriving early.
         done (future (with-open [reader (io/reader completed)]
                        (.readLine reader)))]
     (spit fifo "lazyfs::clear-cache\n")
     (let [signal (deref done timeout-ms ::timeout)]
       (when (= ::timeout signal)
         (future-cancel done)
         (throw (ex-info (str "lazyfs never confirmed the cache was cleared "
                              "(waited " timeout-ms "ms).\n\n"
                              "  why: without that confirmation the kill that "
                              "follows would race the clear, and what the "
                              "power-off actually dropped would be anyone's "
                              "guess.")
                         {:lite/error :lazyfs-clear-timeout
                          :fifo       fifo})))
       signal))))

(defn collect-log!
  "Copies lazyfs's own log next to the run's results, so a surprising verdict
   can be traced back to what the filesystem actually did."
  [{:keys [logfile]} destination]
  (when (and logfile destination (.isFile (io/file logfile)))
    (try
      (io/copy (io/file logfile) (io/file destination))
      destination
      (catch Exception e
        (warn "Couldn't collect the lazyfs log:" (ex-message e))
        nil))))

(defn data-dir
  "Where the target should keep its data: under the mount point, which is the
   only place lazyfs can see its writes."
  ^File [{:keys [mount-point]} & path]
  (apply io/file mount-point path))

(defn quiesce!
  "Best-effort teardown of a handle whose run has ended."
  [handle]
  (try (unmount! handle)
       (catch Exception e (warn "lazyfs teardown:" (ex-message e))))
  (doseq [f [(:fifo handle) (:completed handle)]]
    (when f (.delete (io/file f))))
  nil)

(comment
  ;; The shape of a session, for reference:
  (let [handle (mount! {:dir         "/home/me/lazyfs/lazyfs"
                        :mount-point "/tmp/target/data"
                        :root        "/tmp/target/root"})]
    (clear-cache! handle)               ; => "finished::clear-cache"
    (unmount! handle)))

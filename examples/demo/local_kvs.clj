(ns demo.local-kvs
  "The same four workloads again, against a store Lite runs as a separate OS
   process -- and kills.

   One terminal this time, because Lite starts the target itself:

     clojure -M:run-local counter              # no faults
     clojure -M:run-local counter crash        # kill -9, restart, recover
     clojure -M:run-local counter crash unsafe # a store that buffers -> caught
     clojure -M:run-local set pause            # SIGSTOP / SIGCONT
     clojure -M:run-local bank time=20 concurrency=8

   And, on Linux with lazyfs, the fault a kill -9 can't be:

     clojure -M:run-local counter power-off            # fsyncs -> survives
     clojure -M:run-local counter power-off nofsync    # doesn't -> caught
     clojure -M:run-local counter crash    nofsync     # ... and passes this

   Read this next to `demo/http_kvs.clj`. The adapter is the same one. The
   handlers are the same ones -- this namespace doesn't define any, it uses
   `demo.http-kvs`'s. The store on the other end is the same program. The only
   difference is four lines of `:target`, and what those four lines buy is a
   fault the operating system carries out:

     :http           Lite connects.                       No faults.
     :local-process  Lite starts it and holds the handle. kill -9, SIGSTOP,
                     and -- with a lazyfs mount under the data directory --
                     power-off.

   ## What each fault proves, and what it doesn't

   `crash` is a real SIGKILL: no flush, no close, no shutdown hook, followed by
   a real restart that has to find its data on disk. It tests process death and
   recovery, and it catches a store that acknowledged writes it was still
   holding in its own memory -- which is what `unsafe` simulates.

   What it cannot test is fsync. SIGKILL kills the process, not the kernel, so
   writes the store handed to the OS are written back anyway. `nofsync` is a
   store with exactly that flaw, and it passes `crash` every time.

   `power-off` is the one that asks. lazyfs holds writes until an fsync, so
   clearing its cache drops precisely what the store never synced. Run the last
   two lines above together: the same store, one fault apart, and only one of
   them tells the truth about it.

   Needs Linux, /dev/fuse and a built lazyfs -- point `JEPSEN_LITE_LAZYFS` (or
   `lazyfs=`) at the checkout. Anywhere else, Lite says so and stops rather
   than quietly running a plain crash instead."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [demo.http-kvs :as http-kvs]
            [lite.core :as core])
  (:import (java.io File)
           (java.net ServerSocket)))

(def ^:private driver
  "The program this demo runs as its target: `demo.http-server`, started with a
   data directory of its own.

   Any program would do -- what a `:local-process` target needs is a command
   and a port, not a language. IgelDB is verified this way from its own
   repository (github.com/igel-data/igeldb, `jepsen/`), with a driver that
   embeds it behind this same HTTP API; the handlers there are the same shape
   as the ones here, because the protocol axis doesn't care who is behind it."
  "demo.http-server")

(defn- free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- rm-rf
  "Recursively, because a store keeps its data in directories of its own --
   IgelDB has an sstable/ and a wal/ -- and a shallow delete would leave a run
   reading the last one's data."
  [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf (.listFiles f)))
    (.delete f)))

(defn- java-binary []
  (str (System/getProperty "java.home") File/separator "bin"
       File/separator "java"))

(defn- command
  "The command line Lite will run, signal, and run again. An ordinary program:
   a JVM, this classpath, a namespace's -main. Note that it is the program
   itself and not a shell wrapper -- Lite signals what it started, and a shell
   would take the signal instead of the store."
  [port data-dir {:keys [durability]}]
  [(java-binary)
   "-cp" (System/getProperty "java.class.path")
   "clojure.main" "-m" driver
   "--port"       (str port)
   "--data-dir"   data-dir
   "--durability" (name (or durability :durable))])

(defn config
  "A run config for one workload against a store Lite runs. Options:

     :durability  :durable (default) or :buffered -- does the store keep what
                  it acknowledged when it is killed?
     :data-dir    where the store keeps its data (default ./local-target)
     :nemesis     faults to inject, e.g. [:crash] or [:crash :pause]
     :time-limit  how many seconds to run for
     :concurrency how many workers to run"
  ([workload] (config workload {}))
  ([workload {:keys [data-dir nemesis nemesis-opts time-limit concurrency
                     lazyfs-dir]
              :as   opts}]
   (let [handler  (get http-kvs/handlers workload)
         _        (assert handler (str "No handler for workload "
                                       (pr-str workload)))
         port     (free-port)
         url      (str "http://127.0.0.1:" port)
         base     (or data-dir (str "local-target" File/separator (name workload)))
         ;; With a power-off, the store's data directory has to *be* the lazyfs
         ;; mount -- that is the only place lazyfs can see its writes, and a
         ;; target writing anywhere else would sail through every power-off
         ;; without dropping a thing.
         powering-off? (boolean (some #{:power-off} nemesis))
         data-dir (if powering-off? (str base File/separator "data") base)]
     ;; A fresh directory per run. Note that this is the *demo's* doing, before
     ;; the run, and not the target-type's: Lite starts and kills this process,
     ;; but what it starts it on is the user's business -- and a crash test
     ;; means nothing if the data it is meant to recover was never there, or if
     ;; what it recovers turns out to be the last run's.
     (rm-rf base)
     (.mkdirs (io/file data-dir))
     (cond-> {:adapter  (http-kvs/map->Adapter {:url url})
              :handler  handler
              :workload workload
              :name     (str "jepsen-lite-demo-local-" (name workload))
              :target   (cond-> {:type    :local-process
                                 :command (command port data-dir opts)
                                 :url     url
                                 :log     (str base File/separator "target.log")}
                          powering-off?
                          (assoc :lazyfs
                                 {:dir         (or lazyfs-dir
                                                   (System/getenv "JEPSEN_LITE_LAZYFS"))
                                  :mount-point data-dir
                                  :root        (str base File/separator "root")}))}
       time-limit   (assoc :time-limit time-limit)
       concurrency  (assoc :concurrency concurrency)
       nemesis      (assoc :nemesis nemesis)
       nemesis-opts (assoc :nemesis-opts nemesis-opts)))))

(defn- parse-args
  "Words in any order: a workload name, any of crash / pause / power-off /
   unsafe / nofsync, and settings as key=value, e.g. time=20 concurrency=8."
  [args]
  (let [flags    (set (remove #(str/includes? % "=") args))
        settings (into {} (map #(str/split % #"=" 2))
                       (filter #(str/includes? % "=") args))
        number   (fn [k] (some-> (get settings k) parse-long))
        intents  (cond-> []
                   (flags "crash")     (conj :crash)
                   (flags "power-off") (conj :power-off)
                   (flags "pause")     (conj :pause))]
    [(or (first (filter (comp flags name) (keys http-kvs/handlers))) :counter)
     (cond-> {}
       ;; A store that acknowledges writes it is still holding in its own
       ;; memory. The bug a kill -9 exists to find.
       (flags "unsafe")       (assoc :durability :buffered)
       ;; And one that hands them to the OS but never fsyncs: durable-looking
       ;; under every crash test, and gone the moment the power does.
       (flags "nofsync")      (assoc :durability :no-fsync)
       (seq intents)          (assoc :nemesis intents)
       (get settings "dir")   (assoc :data-dir (get settings "dir"))
       (get settings "lazyfs") (assoc :lazyfs-dir (get settings "lazyfs"))
       (number "time")        (assoc :time-limit (number "time"))
       (number "concurrency") (assoc :concurrency (number "concurrency"))
       ;; Restarting a process takes about a second, so a run needs a clock to
       ;; run against or the faults land after the workload has finished.
       (and (seq intents) (not (number "time")))
       (assoc :time-limit 20))]))

(defn- expected-valid?
  "What a well-wired Lite should say. Which fault catches which store is the
   whole point:

     :buffered  loses acknowledged writes to any kill, so a crash catches it
     :no-fsync  survives a kill -- the kernel writes its page cache back --
                and loses them to a power-off, which is the only fault that
                can tell the two apart"
  [{:keys [durability nemesis]}]
  (let [faults (set nemesis)]
    (not (or (and (seq faults) (= :buffered durability))
             (and (faults :power-off) (= :no-fsync durability))))))

(defn -main
  "`clojure -M:run-local [workload] [crash] [power-off] [pause] [unsafe]\n   [nofsync]
   [time=s] [concurrency=n] [dir=path]`

   Exits non-zero if the verdict isn't the one the demo is meant to produce,
   and 2 if Lite refused the run."
  [& args]
  (let [[workload opts] (parse-args args)
        result (try
                 (core/run (config workload opts))
                 (catch clojure.lang.ExceptionInfo e
                   ;; Lite's own refusals -- an impossible fault, a target that
                   ;; won't start -- explain themselves; a stack trace on top
                   ;; would only bury the explanation.
                   (if (:lite/error (ex-data e))
                     (do (println (str "\n" (ex-message e)))
                         (shutdown-agents)
                         (System/exit 2))
                     (throw e))))
        labels (cond-> [(name workload) "as a local process"]
                 (seq (:nemesis opts))        (conj (str/join
                                                     " " (map name (:nemesis opts))))
                 (= :buffered (:durability opts)) (conj "(unsafe store)"))]
    (println (str "\n" (str/join " " labels) ": :valid? "
                  (pr-str (:valid? result))))
    (shutdown-agents)
    (System/exit (if (= (:valid? result) (expected-valid? opts)) 0 1))))

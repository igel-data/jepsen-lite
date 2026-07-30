(ns lmdb.runner
  "Verify LMDB with jepsen-lite (github.com/igel-data/jepsen-lite), in two
   shapes:

   - **in-process** -- jepsen-lite opens and closes LMDB inside its own JVM.
     `lmdb.in-process` is the adapter; the crash nemesis is `close` then `open`.
   - **a separate process, killed with SIGKILL** -- `lmdb.driver` puts LMDB
     behind a small HTTP API and runs as a program of its own. jepsen-lite
     starts it, `kill -9`s it mid-run, and starts it again; `lmdb.client` is the
     adapter that talks to it.

   The workloads, the checkers and the verdicts are identical in both. What
   differs is the adapter (the protocol LMDB is reached by) and the target-type
   (how it is deployed) -- which is jepsen-lite's whole design premise. The LMDB
   itself is in `lmdb.db`, shared by both.

     :bank      multi-key atomic transfers; the checker proves the total is
                conserved. THE interesting one.
     :register  linearizability (Knossos) over a CAS built from a write txn.
     :counter   read-modify-write in a write transaction.
     :set       durability of acknowledged writes.

   Run:  clojure -M:jepsen                    ; all four, in-process, no faults
         clojure -M:jepsen bank               ; one workload
         clojure -M:jepsen set crash          ; in-process: close + reopen
         clojure -M:jepsen set kill           ; a real kill -9
         clojure -M:jepsen counter pause      ; SIGSTOP / SIGCONT
         clojure -M:jepsen bank time=30 concurrency=8
         clojure -M:jepsen set kill sync=off  ; MDB_NOSYNC -- see the README

   What neither shape tests is loss of writes the OS took but never flushed:
   SIGKILL kills the process, not the page cache. That needs power loss or a
   filesystem fault injector. See the README."
  (:require [clojure.java.io :as jio]
            [clojure.string :as str]
            [lite.core :as core]
            [lmdb.client :as client]
            [lmdb.in-process :as in-process])
  (:import (java.io File)
           (java.net ServerSocket)))

(def ^:private all-workloads [:bank :register :set :counter])

(defn- free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- rm-rf [f]
  (let [f (jio/file f)]
    (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf c)))
    (.delete f)))

(defn- fresh-dir!
  "A clean data directory for one run, before anything starts. That is this
   namespace's job, not jepsen-lite's -- and a crash test means nothing if the
   data it is meant to recover turns out to be the last run's."
  [nm]
  (let [dir (jio/file "./jepsen-data/" nm)]
    (rm-rf dir)
    (.mkdirs dir)
    ;; Canonical, not just absolute. A power-off mounts lazyfs here and then
    ;; confirms it by looking for the mount point in `mount`, which reports the
    ;; path the kernel resolved -- so a "./" left in the middle of ours would
    ;; never match, and the run would fail with lazyfs mounted and working.
    (.getCanonicalPath dir)))

;; ---- in-process ------------------------------------------------------------

(defn config
  "A jepsen-lite run config for `workload` against LMDB in jepsen-lite's own
   JVM. `opts`:

     :nemesis      faults, e.g. [:crash]
     :time-limit   how many seconds to run for
     :concurrency  how many workers issue ops
     :sync?        false for MDB_NOSYNC (default true)"
  [workload {:keys [nemesis time-limit concurrency sync?]}]
  (let [dir (fresh-dir! (name workload))]
    (cond-> {:adapter  (in-process/map->Adapter {:dir dir, :sync? (not= false sync?)})
             :handler  (get in-process/handlers workload)
             :workload workload
             :name     (str "lmdb-" (name workload))
             :target   {:type :in-process}}
      concurrency (assoc :concurrency concurrency)
      nemesis     (assoc :nemesis nemesis)
      ;; A close-and-reopen costs milliseconds, not the two seconds a JVM
      ;; restart does, so the faults can come far more often than the
      ;; `:local-process` ones -- but still on a clock, because without one LMDB
      ;; answers so quickly that the run is over before a fault lands.
      nemesis     (assoc :nemesis-opts {:crashes 8, :crash-interval 1})
      ;; Twenty seconds rather than the ten SQLite gets: a durable LMDB commit
      ;; costs a real sync of the data file and then the meta page, so the run
      ;; acknowledges writes in the hundreds per second rather than the hundreds
      ;; of thousands, and a shorter clock would leave the crashes with very
      ;; little to have lost.
      (or time-limit nemesis) (assoc :time-limit (or time-limit 20)))))

;; ---- kill -9: LMDB in a separate process -----------------------------------
;;
;; The same workloads against the same environment, with one difference: LMDB is
;; running in a process of its own, and jepsen-lite is holding the handle. The
;; adapter changes because the protocol does (HTTP, not method calls); the
;; target changes because the deployment does. Nothing else moves.

(defn- driver-command
  "An ordinary command line: this JVM, this classpath, the driver's -main.

   The program itself and not a shell wrapper -- jepsen-lite signals the process
   it started, and a shell would take the signal instead of the driver.

   The `--add-opens` are repeated here rather than inherited: this is a fresh
   JVM, it gets none of the run's own flags, and lmdbjava cannot open an
   environment without them. `TieredStopAtLevel=1` because every crash pays for
   a JVM start, and a restart that takes four seconds instead of two halves the
   number of kills a run of a given length can fit in."
  [port data-dir sync?]
  [(str (System/getProperty "java.home") File/separator "bin"
        File/separator "java")
   "-XX:TieredStopAtLevel=1"
   "--add-opens" "java.base/java.nio=ALL-UNNAMED"
   "--add-opens" "java.base/sun.nio.ch=ALL-UNNAMED"
   "-cp" (System/getProperty "java.class.path")
   "clojure.main" "-m" "lmdb.driver"
   "--port"     (str port)
   "--data-dir" data-dir
   "--sync"     (if (false? sync?) "off" "on")])

(defn kill-config
  "A run config for `workload` against LMDB in a killable process.

   With `:power-off` among the faults there is one more thing to arrange, and
   getting it wrong is silent: the driver's data directory has to *be* the
   lazyfs mount point. lazyfs can only drop writes that went through it, so a
   driver writing anywhere else sails through every power-off having lost
   nothing -- and the run passes while testing the opposite of what it claims."
  [workload {:keys [nemesis time-limit concurrency sync? lazyfs-dir]}]
  (let [powering-off? (boolean (some #{:power-off} nemesis))
        ;; "poweroff", not "power-off". lazyfs scans the token after
        ;; `--config-path` for the substring "-o" -- meaning to catch a missing
        ;; value followed by a FUSE option -- and a *path* holding those two
        ;; characters trips it too, whereupon it quietly loads its default
        ;; config with a different fault FIFO and every clear-cache goes
        ;; nowhere. jepsen-lite has a fallback for this; not walking into it is
        ;; cheaper than relying on it.
        base     (fresh-dir! (str (if powering-off? "poweroff-" "kill-")
                                  (name workload)))
        data-dir (if powering-off? (str base File/separator "data") base)
        port     (free-port)
        url      (str "http://127.0.0.1:" port)]
    (.mkdirs (jio/file data-dir))
    (cond-> {:adapter  (client/map->Adapter {:url url})
             :handler  (get client/handlers workload)
             :workload workload
             :name     (str "lmdb-" (if powering-off? "poweroff-" "kill-")
                            (name workload))
             ;; The driver's log lives beside the mount and never under it, or
             ;; a power-off would drop the driver's own account of what it did.
             :target   (cond-> {:type    :local-process
                                :command (driver-command port data-dir sync?)
                                :url     url
                                :log     (str base File/separator "driver.log")}
                         powering-off?
                         (assoc :lazyfs
                                {:dir         (or lazyfs-dir
                                                  (System/getenv "JEPSEN_LITE_LAZYFS"))
                                 :mount-point data-dir
                                 :root        (str base File/separator "root")}))}
      concurrency (assoc :concurrency concurrency)
      nemesis     (assoc :nemesis nemesis)
      ;; A restart costs about two seconds. Leave a healthy window after it so
      ;; there is a real population of acknowledged writes between faults;
      ;; back-to-back ones would only ever test connection refusal. A power-off
      ;; also has to wait for lazyfs to confirm the cache is clear, so it gets
      ;; a little more room again.
      nemesis     (assoc :nemesis-opts
                         {:fault-interval (if powering-off? 4 3)})
      ;; Restarting a JVM takes about two seconds, so a run needs a clock to run
      ;; against or the faults land after the workload has finished.
      true        (assoc :time-limit (or time-limit (if nemesis 20 5))))))

;; ---- the runner ------------------------------------------------------------

(defn run-workload
  "Runs one workload and returns jepsen-lite's verdict map."
  [workload {:keys [kill?] :as opts}]
  (println (str "\n==== " (name workload)
                (when kill? " (separate process)")
                (when (seq (:nemesis opts))
                  (str " " (str/join " " (map name (:nemesis opts)))))
                (when (false? (:sync? opts)) " nosync")
                " ===="))
  (core/run (if kill?
              (kill-config workload opts)
              (config workload opts))))

(defn- parse-args
  "Words in any order: workload names (default: all four), `crash`, `kill`,
   `pause`, `power-off`, and `<key>=<value>` for time, concurrency, sync and
   lazyfs.

   `kill`, `pause` and `power-off` all mean a separate process, because none of
   them is possible against an object in jepsen-lite's own JVM: there is nothing
   for the kernel to signal and nowhere to put a filesystem. `kill` means the
   fault too -- a separate process with nothing killing it would just be a
   slower in-process run -- and `power-off` is its own fault, not an addition
   to `crash`: it clears lazyfs's cache and *then* kills."
  [args]
  (let [flags    (set (remove #(str/includes? % "=") args))
        settings (into {} (map #(str/split % #"=" 2))
                       (filter #(str/includes? % "=") args))
        chosen   (filterv (comp flags name) all-workloads)
        kill?    (boolean (some flags ["kill" "pause" "power-off"]))
        intents  (cond-> []
                   (some flags ["crash" "kill"]) (conj :crash)
                   (flags "power-off")           (conj :power-off)
                   (flags "pause")               (conj :pause))]
    [(if (seq chosen) chosen all-workloads)
     (cond-> {}
       kill?                        (assoc :kill? true)
       (seq intents)                (assoc :nemesis intents)
       (get settings "time")        (assoc :time-limit
                                           (parse-long (get settings "time")))
       (get settings "concurrency") (assoc :concurrency
                                           (parse-long (get settings "concurrency")))
       (= "off" (get settings "sync")) (assoc :sync? false)
       (get settings "lazyfs")      (assoc :lazyfs-dir (get settings "lazyfs")))]))

(defn -main
  "clojure -M:jepsen [workload...] [crash] [kill] [pause] [power-off]
                     [time=n] [concurrency=n] [sync=on|off]
                     [lazyfs=/path/to/lazyfs/lazyfs]"
  [& args]
  (let [[workloads opts] (parse-args args)
        results (doall (for [w workloads]
                         [w (:valid? (run-workload w opts))]))]
    (println "\n==== summary ====")
    (doseq [[w valid?] results]
      (println (format "  %-10s :valid? %s" (name w) (pr-str valid?))))
    (shutdown-agents)
    ;; Only `true` is a pass. A checker can also answer `:unknown` -- the `:set`
    ;; workload does when its final read never completed, which is what a store
    ;; that cannot be reopened at all looks like -- and `:unknown` is truthy in
    ;; Clojure, so testing it for truth would report a run that reached no
    ;; verdict as a success.
    (System/exit (if (every? (comp true? second) results) 0 1))))

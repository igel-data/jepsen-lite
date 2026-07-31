(ns sqlite.runner
  "Verify SQLite with jepsen-lite (github.com/igel-data/jepsen-lite), in two
   shapes:

   - **in-process** -- jepsen-lite opens and closes SQLite inside its own JVM.
     `sqlite.in-process` is the adapter; the crash nemesis is `close` then
     `open`, a clean shutdown that checkpoints the WAL and reopens the
     checkpointed database.
   - **a separate process, killed with SIGKILL** -- `sqlite.driver` puts SQLite
     behind a small HTTP API and runs as a program of its own. jepsen-lite
     starts it, `kill -9`s it mid-run, and starts it again; `sqlite.client` is
     the adapter that talks to it. Recovery has to come from what actually
     reached the disk, including an uncheckpointed WAL.

   The workloads, the checkers and the verdicts are identical in both. What
   differs is the adapter (the protocol SQLite is reached by) and the
   target-type (how it is deployed) -- which is jepsen-lite's whole design
   premise, and the reason `kill` cost a driver and a client rather than a
   second test suite. The SQL itself is in `sqlite.db`, shared by both.

     :bank      multi-key atomic transfers; the checker proves the total is
                conserved. THE interesting one.
     :register  linearizability (Knossos) over a single-statement CAS.
     :counter   read-modify-write in a transaction, under contention.
     :set       durability of acknowledged writes.

   Run:  clojure -M:jepsen
         clojure -M:jepsen --workload bank
         clojure -M:jepsen --workload set --fault crash
         clojure -M:jepsen --profile process --workload set --fault crash
         clojure -M:jepsen --profile process --workload counter --fault pause
         clojure -M:jepsen --workload bank --time-limit 30 --concurrency 8
         clojure -M:jepsen --profile process --workload set --journal delete

   What neither shape tests is loss of writes the OS took but never flushed:
   SIGKILL kills the process, not the page cache. That needs power loss or a
   filesystem fault injector, and no amount of `synchronous = FULL` here proves
   anything about it. See the README."
  (:require [clojure.java.io :as jio]
            [sqlite.client :as client]
            [sqlite.in-process :as in-process])
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
  "A jepsen-lite run config for `workload` against SQLite in jepsen-lite's own
   JVM. `opts`:

     :nemesis      faults, e.g. [:crash]
     :time-limit   how many seconds to run for
     :concurrency  how many workers issue ops
     :journal      SQLite journal mode -- \"wal\" (default) or \"delete\""
  [workload {:keys [nemesis time-limit concurrency journal]}]
  (let [dir (fresh-dir! (name workload))]
    (cond-> {:adapter  (in-process/map->Adapter
                        {:db-file (str dir File/separator "sqlite.db")
                         :journal (or journal "wal")})
             :handler  (get in-process/handlers workload)
             :workload workload
             :name     (str "sqlite-" (name workload))
             :target   {:type :in-process}}
      concurrency (assoc :concurrency concurrency)
      nemesis     (assoc :nemesis nemesis)
      ;; A close-and-reopen costs milliseconds, not the two seconds a JVM
      ;; restart does, so the faults can come far more often than the
      ;; `:local-process` ones -- but still on a clock, because without one
      ;; SQLite answers so quickly that the run is over before a fault lands.
      nemesis     (assoc :nemesis-opts {:crashes 8, :crash-interval 1})
      (or time-limit nemesis) (assoc :time-limit (or time-limit 10)))))

;; ---- kill -9: SQLite in a separate process ---------------------------------
;;
;; The same workloads against the same database, with one difference: SQLite is
;; running in a process of its own, and jepsen-lite is holding the handle. The
;; adapter changes because the protocol does (HTTP, not method calls); the
;; target changes because the deployment does. Nothing else moves.

(defn- driver-command
  "An ordinary command line: this JVM, this classpath, the driver's -main.

   The program itself and not a shell wrapper -- jepsen-lite signals the process
   it started, and a shell would take the signal instead of the driver.
   `TieredStopAtLevel=1` because every crash pays for a JVM start, and a restart
   that takes four seconds instead of two halves the number of kills a run of a
   given length can fit in."
  [port data-dir journal sync]
  [(str (System/getProperty "java.home") File/separator "bin"
        File/separator "java")
   "-XX:TieredStopAtLevel=1"
   "-cp" (System/getProperty "java.class.path")
   "clojure.main" "-m" "sqlite.driver"
   "--port"     (str port)
   "--data-dir" data-dir
   "--journal"  journal
   "--sync"     sync])

(defn kill-config
  "A run config for `workload` against SQLite in a killable process.

   With `:power-off` among the faults there is one more thing to arrange, and
   getting it wrong is silent: the driver's data directory has to *be* the
   lazyfs mount point. lazyfs can only drop writes that went through it, so a
   driver writing anywhere else sails through every power-off having lost
   nothing -- and the run passes while testing the opposite of what it claims."
  [workload {:keys [nemesis time-limit concurrency journal sync lazyfs-dir]}]
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
             :name     (str "sqlite-" (if powering-off? "poweroff-" "kill-")
                            (name workload))
             ;; The driver's log lives beside the mount and never under it, or
             ;; a power-off would drop the driver's own account of what it did.
             :target   (cond-> {:type    :local-process
                                :command (driver-command port data-dir
                                                         (or journal "wal")
                                                         (or sync "full"))
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
      ;; acknowledged writes can be checkpointed before the next fault;
      ;; back-to-back ones would only ever test connection refusal. A power-off
      ;; also has to wait for lazyfs to confirm the cache is clear, so it gets
      ;; a little more room again.
      nemesis     (assoc :nemesis-opts
                         {:fault-interval (if powering-off? 4 3)})
      ;; Restarting a JVM takes about two seconds, so a run needs a clock to run
      ;; against or the faults land after the workload has finished.
      true        (assoc :time-limit (or time-limit (if nemesis 20 5))))))

;; ---- the suite -------------------------------------------------------------

(def suite
  "The target-specific part of the test. `lite.runner` owns the CLI, workload
   repetition, summary and exit status; these two builders keep the deployment
   details here with SQLite."
  {:name              "sqlite"
   :workloads         all-workloads
   :default-workloads :all
   :default-profile   :in-process
   :profiles
   {:in-process {:build config}
    :process    {:build kill-config}}
   :options
   {:journal {:values ["wal" "delete"]
              :doc "SQLite journal mode"}
    :sync    {:values ["full" "normal" "off"]
              :doc "SQLite synchronous setting"}
    :lazyfs  {:key :lazyfs-dir
              :doc "path to the lazyfs executable"}}})

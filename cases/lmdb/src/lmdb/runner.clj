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

   Run:  clojure -M:jepsen
         clojure -M:jepsen --workload bank
         clojure -M:jepsen --workload set --fault crash
         clojure -M:jepsen --profile process --workload set --fault crash
         clojure -M:jepsen --profile process --workload counter --fault pause
         clojure -M:jepsen --workload bank --time-limit 30 --concurrency 8
         clojure -M:jepsen --profile process --workload set --sync off

   What neither shape tests is loss of writes the OS took but never flushed:
   SIGKILL kills the process, not the page cache. That needs power loss or a
   filesystem fault injector. See the README."
  (:require [lite.resource :as resource]
            [lmdb.client :as client]
            [lmdb.in-process :as in-process])
  (:import (java.io File)))

(def ^:private all-workloads [:bank :register :set :counter])

;; ---- in-process ------------------------------------------------------------

(defn config
  "A jepsen-lite run config for `workload` against LMDB in jepsen-lite's own
   JVM. `opts`:

     :nemesis      faults, e.g. [:crash]
     :time-limit   how many seconds to run for
     :concurrency  how many workers issue ops
     :sync?        false for MDB_NOSYNC (default true)"
  [workload {:keys [nemesis time-limit concurrency sync?]}]
  (let [dir (resource/run-dir! "./jepsen-data" (name workload))]
    (cond-> {:adapter  (in-process/adapter {:dir dir, :sync? (not= false sync?)})
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
        base     (resource/run-dir!
                  "./jepsen-data"
                  (str (if powering-off? "poweroff-" "kill-")
                       (name workload)))
        data-dir (if powering-off? (str base File/separator "data") base)
        port     (resource/free-port)
        url      (str "http://127.0.0.1:" port)]
    (resource/ensure-dir! data-dir)
    (cond-> {:adapter  (client/adapter {:url url})
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

;; ---- the suite -------------------------------------------------------------

(def suite
  "The target-specific part of the test. `lite.runner` owns the CLI, workload
   repetition, summary and exit status; these two builders keep the deployment
   details here with LMDB."
  {:name              "lmdb"
   :workloads         all-workloads
   :default-workloads :all
   :default-profile   :in-process
   :profiles
   {:in-process {:build config}
    :process    {:build kill-config}}
   :options
   {:sync   {:values ["on" "off"]
             :parse #(not= "off" %)
             :key :sync?
             :doc "LMDB sync mode"}
    :lazyfs {:key :lazyfs-dir
             :doc "path to the lazyfs executable"}}})

(ns lite.power-off-test
  "`:power-off`: the durability question a `kill -9` can't ask.

   SIGKILL kills the process, not the kernel, so writes the target handed to
   the OS get written back anyway. A store that fsyncs what it acknowledges and
   one that merely writes it come through a crash test identically. lazyfs
   moves the boundary: nothing reaches the disk until an fsync, so clearing its
   cache drops exactly what the target failed to sync.

   Two halves, and only one of them needs Linux:

   - the table, the gate and the wiring, which are checked everywhere
   - actually mounting a FUSE filesystem and powering a target off, which
     needs Linux, /dev/fuse and a built lazyfs. Those tests announce that they
     are skipping rather than passing quietly, because a durability test that
     silently didn't run is worse than one that fails.

     JEPSEN_LITE_LAZYFS=/path/to/lazyfs/lazyfs clojure -M:test -n lite.power-off-test"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lite.core :as core]
            [lite.http-targets :as http-targets]
            [lite.lazyfs :as lazyfs]
            [lite.local-process-test :as lp]
            [lite.nemesis :as nemesis]
            [lite.target :as target])
  (:import (java.io File FileOutputStream)))

;; ## Axis 2: where a power-off makes sense at all

(deftest power-off-is-an-intent-of-its-own
  (is (some #{:power-off} nemesis/intents))
  (testing "every target-type has an answer for it"
    (doseq [[target-type row] nemesis/validity]
      (is (contains? row :power-off) (str target-type " is missing power-off")))))

(deftest only-local-process-can-be-powered-off
  (is (= [:local-process]
         (->> nemesis/validity
              (keep (fn [[target-type row]]
                      (when (:power-off row) target-type)))
              vec)))

  (testing "and the others explain why it isn't about signals or networks"
    (doseq [target-type [:in-process :http :compose]]
      (testing target-type
        (let [e   (is (thrown? clojure.lang.ExceptionInfo
                               (nemesis/validate! target-type [:power-off])))
              msg (ex-message e)]
          (is (= :invalid-nemesis (:lite/error (ex-data e))))
          (is (str/includes? msg ":power-off"))
          (is (str/includes? msg "why:"))
          (is (str/includes? msg "fsync") "the reason is about durability")
          (is (str/includes? msg "fix:"))
          (is (str/includes? msg ":local-process")
              "and says where it can be done"))))))

(deftest crash-still-means-what-it-meant
  ;; power-off is additive. :crash is still SIGKILL + restart everywhere it
  ;; was, and nothing about the existing table moved.
  (is (= {:crash true, :pause false, :partition false, :power-off false}
         (:in-process nemesis/validity)))
  (is (= {:crash true, :pause true, :partition false, :power-off true}
         (:local-process nemesis/validity)))
  (is (= [:crash] (nemesis/validate! :in-process [:crash]))))

;; ## The environment gate, which is a separate question

(defn- lazyfs-dir
  "A built lazyfs, if this host has one to point at."
  []
  (System/getenv "JEPSEN_LITE_LAZYFS"))

(defn- runnable-here? []
  (and (lazyfs-dir) (nil? (lazyfs/unavailable (lazyfs-dir)))))

(deftest a-host-that-cannot-power-off-says-so-before-the-run
  (let [target {:type    :local-process
                :command ["true"]
                :url     "http://127.0.0.1:1"
                :lazyfs  {:dir (or (lazyfs-dir) "/nonexistent/lazyfs")
                          :mount-point "/tmp/jepsen-lite-power-off-mnt"
                          :root        "/tmp/jepsen-lite-power-off-root"}}]
    (if (runnable-here?)
      (testing "this host can, so validation lets it through"
        (is (nil? (target/verify-faults! target [:power-off]))))

      (testing "this host can't, and is told what is missing and how to fix it"
        (let [e   (is (thrown? clojure.lang.ExceptionInfo
                               (core/validate! {:target  target
                                                :nemesis [:power-off]})))
              msg (ex-message e)]
          (is (= :power-off-unavailable (:lite/error (ex-data e))))
          (is (str/includes? msg "why:"))
          (is (str/includes? msg "fix:"))
          (testing "and is never quietly downgraded to a plain crash"
            (is (str/includes? msg ":crash"))
            (is (str/includes? msg "won't quietly fall back"))))))

    (testing "the gate is only asked once the fault is valid for the target-type"
      ;; An :in-process power-off is refused by the table, whatever the host
      ;; can do -- otherwise a Mac user would be told to install FUSE for a
      ;; fault their target-type could never carry out.
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (core/validate! {:target  {:type :in-process}
                                            :nemesis [:power-off]})))]
        (is (= :invalid-nemesis (:lite/error (ex-data e))))))))

(deftest a-power-off-without-a-mount-is-refused
  ;; Guards the mistake that makes every power-off run pass: a target whose
  ;; data never goes through lazyfs at all.
  (when (runnable-here?)
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (target/verify-faults!
                          {:type :local-process
                           :lazyfs {:dir (lazyfs-dir)}}
                          [:power-off])))]
      (is (= :power-off-unavailable (:lite/error (ex-data e))))
      (is (str/includes? (ex-message e) "data directory")))))

;; ## The real thing, on Linux

(defn- skip [what]
  (println (str "SKIPPING " what ": needs Linux with /dev/fuse and a built "
                "lazyfs. Set JEPSEN_LITE_LAZYFS=/path/to/lazyfs/lazyfs."))
  true)

(defn- with-lazyfs-target
  "Runs `f` with a data directory on a lazyfs mount, cleaned up afterwards."
  [f]
  (let [base  (str (io/file (System/getProperty "java.io.tmpdir")
                            (str "jepsen-lite-power-off-" (System/nanoTime))))
        mount (str (io/file base "data"))
        root  (str (io/file base "root"))]
    (try
      (f {:dir (lazyfs-dir), :mount-point mount, :root root})
      (finally
        (lazyfs/quiesce! {:dir (lazyfs-dir), :mount-point mount})
        (lp/rm-rf base)))))

(defn- config
  "A run against a target whose data directory is the lazyfs mount point."
  [workload lazyfs-config durability opts]
  (let [port (lp/free-port)
        url  (str "http://127.0.0.1:" port)]
    (merge {:adapter  (http-targets/map->HttpAdapter {:url url})
            :handler  (get http-targets/handlers workload)
            :workload workload
            :name     (str "jepsen-lite-test-power-off-" (name workload))
            :target   {:type    :local-process
                       :command (lp/driver-command port (:mount-point lazyfs-config)
                                                   {:durability durability})
                       :url     url
                       :log     (str (io/file (:root lazyfs-config) "target.log"))
                       :lazyfs  lazyfs-config}}
           opts)))

(deftest lazyfs-drops-what-was-never-fsynced
  ;; The mechanism, before any workload: the fact the whole feature rests on.
  (if-not (runnable-here?)
    (is (skip "power-off: lazyfs mechanics"))
    (with-lazyfs-target
      (fn [lazyfs-config]
        (let [handle (lazyfs/mount! lazyfs-config)]
          (try
            (let [synced   (io/file (:mount-point handle) "synced")
                  unsynced (io/file (:mount-point handle) "unsynced")]
              (with-open [out (FileOutputStream. synced)]
                (.write out (.getBytes "kept\n"))
                (.flush out)
                (.sync (.getFD out)))
              (with-open [out (FileOutputStream. unsynced)]
                (.write out (.getBytes "lost\n"))
                (.flush out))

              (is (= "finished::clear-cache" (lazyfs/clear-cache! handle))
                  "and it waits to be told the cache is gone")

              (testing "fsynced data is still there"
                (is (pos? (.length ^File synced))))
              (testing "and what was never fsynced is not"
                (is (zero? (.length ^File unsynced)))))
            (finally
              (lazyfs/unmount! handle))))))))

(deftest a-target-that-fsyncs-survives-power-loss
  (if-not (runnable-here?)
    (is (skip "power-off: fsyncing target"))
    (with-lazyfs-target
      (fn [lazyfs-config]
        (let [{:keys [valid? history]}
              (core/run (config :counter lazyfs-config :durable
                                {:nemesis      [:power-off]
                                 :time-limit   20
                                 :nemesis-opts {:faults 2, :fault-interval 4}}))
              offs (filter (fn [op] (and (= :power-off (:f op))
                                         (= :info (:type op))
                                         (:value op)))
                           history)]
          (is (seq offs) "the target really was powered off")
          (testing "and kept everything it acknowledged"
            (is (true? valid?))))))))

(deftest a-target-that-never-fsyncs-does-not
  ;; The bug only a power-off can find: this same target survives `:crash`
  ;; untouched, because a SIGKILL leaves the kernel to write its page cache
  ;; back. Nothing was ever wrong with its recovery -- only with its idea of
  ;; what "acknowledged" means.
  (if-not (runnable-here?)
    (is (skip "power-off: fsync-skipping target"))
    (with-lazyfs-target
      (fn [lazyfs-config]
        (let [{:keys [valid? results history]}
              (core/run (config :counter lazyfs-config :no-fsync
                                {:nemesis      [:power-off]
                                 :time-limit   20
                                 :nemesis-opts {:faults 2, :fault-interval 4}}))]
          (testing "the run had acknowledged writes to lose"
            (let [first-off (:time (first (filter (fn [op]
                                                    (and (= :power-off (:f op))
                                                         (:value op)))
                                                  history)))]
              (is (some? first-off))
              (is (seq (filter (fn [op] (and (= :ok (:type op))
                                             (= :add (:f op))
                                             (< (:time op) first-off)))
                               history))
                  "some add was acknowledged before the first power-off")))

          (is (false? valid?))
          (testing "as reads below what the target acknowledged"
            (let [[lower value _upper] (first (:errors results))]
              (is (seq (:errors results)))
              (is (< value lower)))))))))

(deftest a-fsync-skipping-target-survives-a-plain-crash
  ;; The other half of the argument, and the reason power-off had to exist: the
  ;; very same target, killed with SIGKILL instead, loses nothing. A crash test
  ;; would have called it durable.
  (if-not (runnable-here?)
    (is (skip "power-off: crash tells the two apart"))
    (with-lazyfs-target
      (fn [lazyfs-config]
        (let [{:keys [valid?]}
              (core/run (config :counter lazyfs-config :no-fsync
                                {:nemesis      [:crash]
                                 :time-limit   20
                                 :nemesis-opts {:faults 2, :fault-interval 4}}))]
          (is (true? valid?)
              "SIGKILL cannot tell a target that fsyncs from one that doesn't"))))))

(deftest the-mount-is-cleaned-up-after-a-run
  (if-not (runnable-here?)
    (is (skip "power-off: teardown"))
    (let [base  (str (io/file (System/getProperty "java.io.tmpdir")
                              (str "jepsen-lite-power-off-teardown-"
                                   (System/nanoTime))))
          mount (str (io/file base "data"))
          lazyfs-config {:dir (lazyfs-dir), :mount-point mount
                         :root (str (io/file base "root"))}]
      (try
        (core/run (config :counter lazyfs-config :durable
                          {:workload-opts {:op-limit 20}}))
        (testing "no FUSE mount is left behind to break the next run"
          (is (not (str/includes? (:out (shell/sh "mount")) mount))))
        (finally
          (lazyfs/quiesce! lazyfs-config)
          (lp/rm-rf base))))))

;; ## The client side never learns any of this happened

(deftest power-off-changes-nothing-a-handler-can-see
  ;; Orthogonality: the same adapter and handlers as every other target-type,
  ;; and the ops in the history are the workload's own.
  (is (= (set (keys http-targets/handlers)) #{:register :set :counter :bank}))
  (is (contains? (methods target/verify-faults!) :local-process)
      "the host check belongs to the target-type, not to the core")
  (is (nil? (target/verify-faults! {:type :in-process} [:crash]))
      "and target-types with nothing to check say nothing"))

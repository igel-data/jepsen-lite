(ns demo.compose-kvs
  "The same four workloads once more, against the store running as a
   docker-compose service -- and the only target-type that can be partitioned.

     clojure -M:run-compose counter                # no faults
     clojure -M:run-compose counter crash          # SIGKILL the container
     clojure -M:run-compose counter pause          # freeze it
     clojure -M:run-compose set partition          # cut it off the network
     clojure -M:run-compose bank time=60 concurrency=8

   Needs Docker running. Lite brings the service up before the run and takes it
   down after, and Pumba -- itself a container, so there is nothing to install
   -- carries out the faults.

   Read this next to `demo/local_kvs.clj` and `demo/http_kvs.clj`. The adapter
   is the same one; the handlers are `demo.http-kvs`'s, again; the store is the
   same program. What changes is four lines of `:target`, and what those buy is
   a third kind of fault:

     :http           Lite connects.              No faults.
     :local-process  Lite holds a process.       kill -9, SIGSTOP.
     :compose        Lite holds a container.     kill -9, freeze, partition.

   Partition is the one that needed a container. A local process talks to Lite
   over loopback, and there is no network in between to cut."
  (:require [clojure.string :as str]
            [demo.http-kvs :as http-kvs]
            [lite.core :as core]))

(def compose-file "examples/compose/docker-compose.yml")

(def ^:private url
  "The port the compose file publishes."
  "http://127.0.0.1:8080")

(defn config
  "A run config for one workload against the containerized store. Options:

     :nemesis     faults to inject, e.g. [:crash] or [:partition]
     :time-limit  how many seconds to run for
     :concurrency how many workers to run"
  ([workload] (config workload {}))
  ([workload {:keys [nemesis nemesis-opts time-limit concurrency]}]
   (let [handler (get http-kvs/handlers workload)]
     (assert handler (str "No handler for workload " (pr-str workload)))
     (cond-> {:adapter  (http-kvs/map->Adapter {:url url})
              :handler  handler
              :workload workload
              :name     (str "jepsen-lite-demo-compose-" (name workload))
              :target   {:type      :compose
                         :file      compose-file
                         :service   "kvs"
                         :container "jepsen-lite-kvs"
                         :url       url
                         ;; The first run builds the image, which is not fast.
                         :ready-timeout 300000
                         ;; Shorter than the interval between faults, so each
                         ;; one has healed before the next arrives.
                         :fault-duration "5s"}}
       time-limit   (assoc :time-limit time-limit)
       concurrency  (assoc :concurrency concurrency)
       nemesis      (assoc :nemesis nemesis)
       nemesis-opts (assoc :nemesis-opts nemesis-opts)))))

(defn- parse-args
  "Words in any order: a workload name, any of crash / pause / partition, and
   settings as key=value, e.g. time=60 concurrency=8."
  [args]
  (let [flags    (set (remove #(str/includes? % "=") args))
        settings (into {} (map #(str/split % #"=" 2))
                       (filter #(str/includes? % "=") args))
        number   (fn [k] (some-> (get settings k) parse-long))
        ;; `flags` holds strings, so match on the intent's name -- filtering
        ;; keywords through it would quietly find nothing and run no faults.
        intents  (filterv (comp flags name) [:crash :pause :partition])]
    [(or (first (filter (comp flags name) (keys http-kvs/handlers))) :counter)
     (cond-> {}
       (seq intents)          (assoc :nemesis intents)
       (number "time")        (assoc :time-limit (number "time"))
       (number "concurrency") (assoc :concurrency (number "concurrency"))
       ;; Container faults are slower than in-process ones -- a restart is a
       ;; container start, and a pause lasts five seconds -- so a run needs a
       ;; clock to run against or the faults land after the workload is done.
       (and (seq intents) (not (number "time")))
       (assoc :time-limit 60))]))

(defn -main
  "`clojure -M:run-compose [workload] [crash] [pause] [partition] [time=s]
   [concurrency=n]`. Exits 2 if Lite refused the run."
  [& args]
  (let [[workload opts] (parse-args args)
        result (try
                 (core/run (config workload opts))
                 (catch clojure.lang.ExceptionInfo e
                   (if (:lite/error (ex-data e))
                     (do (println (str "\n" (ex-message e)))
                         (shutdown-agents)
                         (System/exit 2))
                     (throw e))))
        labels (cond-> [(name workload) "in a container"]
                 (seq (:nemesis opts))
                 (conj (str/join " " (map name (:nemesis opts)))))]
    (println (str "\n" (str/join " " labels) ": :valid? "
                  (pr-str (:valid? result))))
    (shutdown-agents)
    (System/exit (if (true? (:valid? result)) 0 1))))

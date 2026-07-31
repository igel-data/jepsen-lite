(ns lite.scaffold
  "Creates a minimal, runnable Jepsen Lite consumer project."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file Path)))

(def ^:private project-pattern
  #"[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*")

(defn- invalid! [message data]
  (throw (ex-info message (assoc data :lite/error :invalid-scaffold-input))))

(defn- namespace-path [project]
  (-> project
      (str/replace "-" "_")
      (str/replace "." "/")))

(defn- relative-path [from to]
  (let [^Path from (.toPath (io/file from))
        ^Path to   (.toPath (io/file to))]
    (str (.relativize (.toAbsolutePath from) (.toAbsolutePath to)))))

(defn- dependency-edn [{:keys [lite-root lite-version]} output]
  (if lite-version
    (binding [*print-namespace-maps* false]
      (pr-str {:mvn/version lite-version}))
    (let [root (io/file lite-root)]
      (when-not (and (.isFile (io/file root "deps.edn"))
                     (.isFile (io/file root "src/lite/core.clj")))
        (invalid! (str "Jepsen Lite was not found at " lite-root ".")
                  {:field :lite-root, :value lite-root}))
      (binding [*print-namespace-maps* false]
        (pr-str {:local/root (relative-path output root)})))))

(defn- deps-template [project dependency]
  (format
   "{:paths [\"src\"]

 :deps {org.clojure/clojure          {:mvn/version \"1.12.5\"}
        com.igel-data/jepsen-lite %s}

 :aliases
 {:jepsen {:main-opts [\"-m\" \"lite.runner\" \"%s.runner/suite\"]}
  :test   {:extra-paths [\"test\"]
           :extra-deps {io.github.cognitect-labs/test-runner
                        {:git/tag \"v0.5.1\" :git/sha \"dfb30dd\"}}
           :main-opts [\"-m\" \"cognitect.test-runner\"]}}}
"
   dependency project))

(defn- target-template [project]
  (format
   "(ns %s.target
  (:require [lite.client :as client]
            [lite.handlers :as handlers]))

;; Replace these five small functions with calls to your target. The atom keeps
;; this starter runnable; it is not part of Jepsen Lite's API.
(defonce ^:private store (atom {}))

(defn open! [] store)
(defn close! [_conn] nil)

(defn read-register [conn key]
  (get @conn key))

(defn write-register! [conn key value]
  (swap! conn assoc key value))

(defn cas-register! [conn key old new]
  (loop []
    (let [values @conn]
      (if (not= old (get values key))
        false
        (if (compare-and-set! conn values (assoc values key new))
          true
          (recur))))))

(defn adapter []
  (client/adapter {:open open!, :close close!}))

(def handlers
  {:register
   (handlers/register {:read read-register
                       :write write-register!
                       :cas cas-register!})})
"
   project))

(defn- runner-template [project]
  (format
   "(ns %s.runner
  (:require [%s.target :as target]))

(defn config [workload _opts]
  {:adapter  (target/adapter)
   :handler  (get target/handlers workload)
   :workload workload
   :target   {:type :in-process}})

;; lite.runner owns -main, argument parsing, repetition, summaries, and exit
;; status. This project declares only what differs for its target.
(def suite
  {:name              \"%s\"
   :workloads         [:register]
   :default-workloads :register
   :default-profile   :in-process
   :profiles          {:in-process {:build config}}})
"
   project project project))

(defn- test-template [project]
  (format
   "(ns %s.target-test
  (:require [clojure.test :refer [deftest is]]
            [lite.client :as client]
            [%s.target :as target]))

(deftest register-operations-reach-the-target
  (let [adapter (assoc (target/adapter)
                       :handler (:register target/handlers))
        conn    (client/open adapter)]
    (try
      (is (= :ok (:type (client/invoke
                         adapter conn
                         {:type :invoke, :f :write, :key 0, :value 7}))))
      (is (= 7 (:value (client/invoke
                        adapter conn
                        {:type :invoke, :f :read, :key 0, :value nil}))))
      (finally (client/close adapter conn)))))
"
   project project))

(defn- readme-template [project]
  (format
   "# %s Jepsen Lite test

This project is runnable as generated:

```sh
clojure -M:jepsen --help
clojure -M:jepsen --time-limit 5
clojure -M:test
```

To connect your target, edit `src/%s/target.clj`:

1. replace `open!` and `close!` with its connection lifecycle;
2. replace `read-register`, `write-register!`, and `cas-register!` with target
   calls;
3. add the target's driver dependency to `deps.edn`.

The suite has no `-main` or argument parser. `lite.runner` supplies both. Add
workloads with `lite.handlers/set`, `counter`, or `bank`, then list them in the
suite's `:workloads`.
"
   project (namespace-path project)))

(defn create!
  "Creates a starter project and returns its canonical directory.

   Required: `:name`. `:output` defaults to the name. By default the generated
   dependency points to `:lite-root` (the current directory); pass
   `:lite-version` to generate a Maven dependency instead. Existing output is
   never overwritten."
  [{:keys [name output lite-root lite-version]
    :or {lite-root (System/getProperty "user.dir")}}]
  (when-not (and (string? name) (re-matches project-pattern name))
    (invalid! "Project name must be lowercase kebab-case, optionally namespaced with dots."
              {:field :name, :value name}))
  (when (and lite-version (not (string? lite-version)))
    (invalid! ":lite-version must be a string."
              {:field :lite-version, :value lite-version}))
  (let [output     (io/file (or output name))
        source-dir (namespace-path name)
        dependency (dependency-edn {:lite-root lite-root
                                    :lite-version lite-version}
                                   (.getCanonicalFile output))
        files      {"deps.edn" (deps-template name dependency)
                    (str "src/" source-dir "/target.clj")
                    (target-template name)
                    (str "src/" source-dir "/runner.clj")
                    (runner-template name)
                    (str "test/" source-dir "/target_test.clj")
                    (test-template name)
                    "README.md" (readme-template name)
                    ".gitignore" "/.cpcache\n/store\n"}]
    (when (.exists output)
      (invalid! (str "Refusing to overwrite existing path " output ".")
                {:field :output, :value (.getPath output)}))
    (when-not (.mkdirs output)
      (invalid! (str "Could not create " output ".")
                {:field :output, :value (.getPath output)}))
    (doseq [[path contents] files]
      (let [file (io/file output path)]
        (.mkdirs (.getParentFile file))
        (spit file contents)))
    (.getCanonicalPath output)))

(def ^:private usage
  (str "Create a runnable Jepsen Lite test project.\n\n"
       "Usage:\n"
       "  clojure -M:new NAME [DIRECTORY]\n"
       "  clojure -M:new --lite-version VERSION NAME [DIRECTORY]\n\n"
       "Without --lite-version, the project depends on this working copy."))

(defn- parse-args [args]
  (loop [remaining args, parsed {}]
    (let [[arg & more] remaining]
      (cond
        (nil? arg) parsed
        (= "--help" arg) (assoc parsed :help? true)
        (= "--lite-version" arg)
        (if-let [version (first more)]
          (recur (next more) (assoc parsed :lite-version version))
          (invalid! "--lite-version needs a value." {:option arg}))
        (str/starts-with? arg "-")
        (invalid! (str "Unknown option " arg ".") {:option arg})
        (nil? (:name parsed)) (recur more (assoc parsed :name arg))
        (nil? (:output parsed)) (recur more (assoc parsed :output arg))
        :else (invalid! "Too many positional arguments." {:args args})))))

(defn -main [& args]
  (try
    (let [{:keys [help?] :as opts} (parse-args args)]
      (if help?
        (println usage)
        (if (:name opts)
          (println "Created" (create! opts))
          (do (binding [*out* *err*] (println usage))
              (System/exit 1)))))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (ex-message e))
        (println)
        (println usage))
      (System/exit 1))))

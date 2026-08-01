(ns lite.scaffold-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lite.scaffold :as scaffold])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- delete-tree! [file]
  (let [file (io/file file)]
    (when (.isDirectory file)
      (doseq [child (.listFiles file)] (delete-tree! child)))
    (.delete file)))

(defn- temp-root []
  (.toFile (Files/createTempDirectory "jepsen-lite-scaffold-test"
                                      (make-array FileAttribute 0))))

(deftest the-generated-suite-honours-the-command-line
  ;; The generated project's README tells a newcomer to run `--time-limit 5`,
  ;; and its help offers `--fault`. If the generated `config` dropped the opts
  ;; it was handed, both would be accepted and ignored -- and a run asked for a
  ;; crash would report a green verdict having crashed nothing. This loads the
  ;; generated code and asks it directly.
  (let [root (temp-root)
        out  (io/file root "honours")]
    (try
      (scaffold/create! {:name "honours", :output out
                         :lite-root (System/getProperty "user.dir")})
      (load-file (.getPath (io/file out "src/honours/target.clj")))
      (load-file (.getPath (io/file out "src/honours/runner.clj")))
      (let [config (resolve 'honours.runner/config)
            built  (config :register {:nemesis     [:crash]
                                      :time-limit  7
                                      :concurrency 3})]
        (is (= [:crash] (:nemesis built)) "--fault reaches the run config")
        (is (= 7 (:time-limit built))     "--time-limit reaches the run config")
        (is (= 3 (:concurrency built))    "--concurrency reaches the run config")

        (testing "and the suite declares what its profile can be asked for"
          (let [suite (var-get (resolve 'honours.runner/suite))]
            (is (= :in-process
                   (get-in suite [:profiles :in-process :target-type]))))))
      (finally
        (remove-ns 'honours.runner)
        (remove-ns 'honours.target)
        (delete-tree! root)))))

(deftest creates-a-complete-project-without-overwriting
  (let [root (.toFile (Files/createTempDirectory
                       "jepsen-lite-scaffold-test"
                       (make-array FileAttribute 0)))
        out  (io/file root "example")]
    (try
      (let [created (scaffold/create! {:name "acme.example-store"
                                       :output out})]
        (is (= (.getCanonicalPath out) created))
        (doseq [path ["deps.edn"
                      "src/acme/example_store/target.clj"
                      "src/acme/example_store/runner.clj"
                      "test/acme/example_store/target_test.clj"
                      "README.md"
                      ".gitignore"]]
          (is (.isFile (io/file out path)) path))
        (is (str/includes? (slurp (io/file out "deps.edn"))
                           "acme.example-store.runner/suite"))
        (let [deps       (edn/read-string (slurp (io/file out "deps.edn")))
              local-root (get-in deps
                                 [:deps
                                  'com.igel-data/jepsen-lite
                                  :local/root])]
          (is (= (.getCanonicalPath (io/file "."))
                 (.getCanonicalPath (io/file out local-root)))))
        (testing "an existing project is never overwritten"
          (is (= :invalid-scaffold-input
                 (:lite/error
                  (ex-data
                   (try
                     (scaffold/create! {:name "acme.example-store"
                                        :output out})
                     (catch clojure.lang.ExceptionInfo e e))))))))
      (finally (delete-tree! root)))))

(deftest validates-project-name
  (is (= :invalid-scaffold-input
         (:lite/error
          (ex-data
           (try
             (scaffold/create! {:name "../Not Safe"})
             (catch clojure.lang.ExceptionInfo e e)))))))

(deftest can-generate-a-released-dependency
  (let [root (.toFile (Files/createTempDirectory
                       "jepsen-lite-scaffold-version-test"
                       (make-array FileAttribute 0)))
        out  (io/file root "versioned")]
    (try
      (scaffold/create! {:name "versioned"
                         :output out
                         :lite-version "1.2.3"})
      (is (str/includes? (slurp (io/file out "deps.edn"))
                         "{:mvn/version \"1.2.3\"}"))
      (finally (delete-tree! root)))))

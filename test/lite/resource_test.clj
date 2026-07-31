(ns lite.resource-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [lite.resource :as resource])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- delete-tree! [file]
  (let [file (io/file file)]
    (when (.isDirectory file)
      (doseq [child (.listFiles file)] (delete-tree! child)))
    (.delete file)))

(deftest run-directories-are-fresh-and-never-delete-an-old-one
  (let [root (.toFile (Files/createTempDirectory
                       "jepsen-lite-resource-test"
                       (make-array FileAttribute 0)))]
    (try
      (let [first-dir  (resource/run-dir! root "../set workload")
            marker     (io/file first-dir "marker")
            _          (spit marker "keep")
            second-dir (resource/run-dir! root "../set workload")]
        (is (not= first-dir second-dir))
        (is (.isDirectory (io/file first-dir)))
        (is (.isDirectory (io/file second-dir)))
        (is (.isFile marker))
        (is (.startsWith (.getCanonicalPath (io/file first-dir))
                         (.getCanonicalPath root))))
      (finally (delete-tree! root)))))

(deftest free-port-returns-a-bindable-port-number
  (let [port (resource/free-port)]
    (is (<= 1 port 65535))))

(deftest ensure-dir-rejects-an-existing-file
  (let [path (.toFile (Files/createTempFile
                       "jepsen-lite-resource-test"
                       ".file"
                       (make-array FileAttribute 0)))]
    (try
      (is (= :invalid-run-directory
             (:lite/error
              (ex-data
               (try
                 (resource/ensure-dir! path)
                 (catch clojure.lang.ExceptionInfo e e))))))
      (finally (.delete path)))))

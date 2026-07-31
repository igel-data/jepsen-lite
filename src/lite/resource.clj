(ns lite.resource
  "Small, safe resource helpers for target config builders."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net ServerSocket)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)
           (java.util UUID)))

(def ^:private run-id-format
  (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss.SSS"))

(defn free-port
  "Asks the OS for an unused TCP port and returns its number.

   The socket is released before the target starts, so this is suitable for
   local test processes but is not a reservation against another process racing
   for the same port."
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn ensure-dir!
  "Creates `path` and its parents if needed, then returns its canonical path.
   Throws if the path exists but is not a directory."
  [path]
  (let [dir (io/file path)]
    (when (and (.exists dir) (not (.isDirectory dir)))
      (throw (ex-info (str "Cannot create directory " path
                           ": the path is already a file.")
                      {:lite/error :invalid-run-directory, :path path})))
    ;; A concurrent creator may win between the first check and mkdirs.
    (when-not (or (.isDirectory dir) (.mkdirs dir) (.isDirectory dir))
      (throw (ex-info (str "Could not create directory " path ".")
                      {:lite/error :invalid-run-directory, :path path})))
    (.getCanonicalPath dir)))

(defn- safe-label [label]
  (let [label (-> (if (keyword? label) (name label) (str label))
                  (str/replace #"[^A-Za-z0-9_-]+" "-")
                  (str/replace #"^-+|-+$" ""))]
    (if (str/blank? label) "run" label)))

(defn run-dir!
  "Creates a new, never-reused directory under `root/label` and returns its
   canonical path. It never deletes or empties an existing path.

     (run-dir! root :register)

   A unique directory makes a run start fresh without risking deletion of a
   user-supplied data directory."
  ([label]
   (run-dir! "jepsen-data" label))
  ([root label]
   (let [parent (ensure-dir! (io/file root (safe-label label)))]
     (loop []
       (let [run-id (str (.format run-id-format (LocalDateTime/now)) "-"
                         (UUID/randomUUID))
             dir    (io/file parent run-id)]
         (cond
           (.mkdir dir) (.getCanonicalPath dir)
           (.exists dir) (recur)
           :else (throw (ex-info (str "Could not create run directory " dir ".")
                                 {:lite/error :invalid-run-directory
                                  :path (.getPath dir)}))))))))

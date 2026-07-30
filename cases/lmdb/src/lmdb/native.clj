(ns lmdb.native
  "Finds liblmdb and tells lmdbjava where it is, before anything touches it.

   lmdbjava is a JNR-FFI binding rather than a library that carries its own
   native code, and the pre-built natives it can extract cover linux-x86_64,
   osx-x86_64 and windows-x86_64 -- notably not arm64 macOS. Rather than depend
   on which of those happens to match, this suite asks for one rule everywhere:
   **a liblmdb installed on the system**.

     brew install lmdb          # macOS
     apt install liblmdb0       # Debian / Ubuntu
     dnf install lmdb-libs      # Fedora

   Require this namespace before importing anything from `org.lmdbjava`; it sets
   the system property lmdbjava reads when it loads the library. Setting it
   afterwards would be too late, and the error when it is would be a JNR link
   failure rather than a sentence anyone can act on -- which is the whole reason
   this namespace exists rather than a line in `deps.edn`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private property "lmdbjava.native.lib")

(def ^:private env-var
  "An escape hatch for a liblmdb somewhere none of the usual paths reach."
  "LMDB_NATIVE_LIB")

(def ^:private candidates
  "Both the unversioned name and the soname. The bare `liblmdb.so` is a symlink
   that only the -dev package installs, and a machine that just runs things has
   `liblmdb.so.0` and nothing else -- which is the usual case in a container."
  ["/opt/homebrew/lib/liblmdb.dylib"
   "/usr/local/lib/liblmdb.dylib"
   "/usr/local/lib/liblmdb.so"
   "/usr/local/lib/liblmdb.so.0"
   "/usr/lib/aarch64-linux-gnu/liblmdb.so"
   "/usr/lib/aarch64-linux-gnu/liblmdb.so.0"
   "/usr/lib/x86_64-linux-gnu/liblmdb.so"
   "/usr/lib/x86_64-linux-gnu/liblmdb.so.0"
   "/usr/lib64/liblmdb.so"
   "/usr/lib64/liblmdb.so.0"
   "/usr/lib/liblmdb.so"
   "/usr/lib/liblmdb.so.0"])

(defn- existing
  [path]
  (when (and path (.isFile (io/file path))) path))

(defn locate!
  "Points lmdbjava at a liblmdb, and returns the path it settled on.

   An explicit choice wins over a found one, in the order someone would expect:
   `-Dlmdbjava.native.lib`, then `$LMDB_NATIVE_LIB`, then the usual places."
  []
  (or (System/getProperty property)
      (when-let [path (or (existing (System/getenv env-var))
                          (some existing candidates))]
        (System/setProperty property path)
        path)
      (throw (ex-info
              (str "Couldn't find liblmdb.\n\n"
                   "  why: lmdbjava binds to a native LMDB rather than carrying"
                   " one, and this suite asks for a system install so that every"
                   " platform works the same way -- including arm64 macOS, which"
                   " lmdbjava publishes no pre-built native for.\n\n"
                   "  fix: install it --\n\n"
                   "    brew install lmdb        (macOS)\n"
                   "    apt install liblmdb0     (Debian / Ubuntu)\n"
                   "    dnf install lmdb-libs    (Fedora)\n\n"
                   "  or, if it is somewhere unusual, set " env-var
                   " to the library itself.\n\n"
                   "  looked in: " (str/join ", " candidates))
              {:lmdb/error :native-library-not-found
               :searched   candidates}))))

;; Requiring this namespace is the point: the property has to be set before
;; lmdbjava's Library class initializes, and a caller that had to remember to
;; call something would eventually forget.
(defonce ^{:doc "The liblmdb this JVM is bound to."} library-path (locate!))

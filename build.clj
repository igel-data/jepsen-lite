(ns build
  "Builds and publishes the library jar.

   The jar is `src` and nothing else: `examples/` and `test/` are on the
   classpath only for their own aliases, so depending on jepsen-lite brings in
   the library and not the demos.

   `VERSION` comes from the environment -- the release workflow sets it from
   the git tag -- so a local build is always a snapshot and can't be mistaken
   for a release."
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.igel-data/jepsen-lite)
(def version (or (System/getenv "VERSION") "0.0.0-SNAPSHOT"))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn- write-version!
  "Records the version being built, so the code can name itself.

   `lite.scaffold` needs it: a generated project has to depend on *this*
   Jepsen Lite, and a jar has no other way to know which release it is."
  []
  (let [file (b/resolve-path (str class-dir "/lite/version.edn"))]
    (b/write-file {:path (str file)
                   :string (pr-str {:lib (str lib), :version version})})))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src"]
                :pom-data [[:description
                            (str "A lightweight, scoped-down fault-injection "
                                 "and verification tool built on Jepsen's "
                                 "internals")]
                           [:url "https://github.com/igel-data/jepsen-lite"]
                           [:licenses
                            [:license
                             [:name "Eclipse Public License 2.0"]
                             [:url "https://www.eclipse.org/legal/epl-2.0/"]]]
                           [:scm
                            [:url "https://github.com/igel-data/jepsen-lite"]
                            [:connection
                             "scm:git:https://github.com/igel-data/jepsen-lite.git"]
                            [:developerConnection
                             "scm:git:ssh://git@github.com/igel-data/jepsen-lite.git"]
                            [:tag (str "v" version)]]]})
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (write-version!)
  (b/copy-file {:src    "LICENSE"
                :target (str class-dir "/META-INF/LICENSE")})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  {:jar-file jar-file})

(defn install [_]
  (jar nil)
  (dd/deploy {:installer :local
              :artifact  (b/resolve-path jar-file)
              :pom-file  (b/pom-path {:lib lib, :class-dir class-dir})}))

(defn deploy [_]
  (dd/deploy {:installer :remote
              :artifact  (b/resolve-path jar-file)
              :pom-file  (b/pom-path {:lib lib, :class-dir class-dir})}))

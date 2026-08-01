(ns lite.runner
  "The common command-line runner for a Jepsen Lite suite.

   A suite names the workloads a target supports, the profiles it can run
   under, and any target-specific command-line options:

     {:name              \"sqlite\"
      :workloads         [:bank :register :set :counter]
      :default-workloads :all
      :default-profile   :in-process
      :profiles
      {:in-process {:build config}
       :process    {:build kill-config}}
      :options
      {:journal {:values [\"wal\" \"delete\"]}
       :sync    {:values [\"full\" \"normal\" \"off\"]}
       :lazyfs {:key :lazyfs-dir}}}

   A profile's `:build` is `(fn [workload opts] core-config)`. This deliberately
   matches the config functions consumers already write: M7.1 centralizes the
   CLI, repetition, summary and exit status without introducing a second config
   language."
  (:require [clojure.string :as str]
            [lite.core :as core]
            [lite.nemesis :as nemesis]))

(def ^:private faults
  [:crash :pause :partition :power-off])

(def ^:private reserved-options
  #{"--workload" "--all-workloads" "--profile" "--fault"
    "--time-limit" "--concurrency" "--list" "--help"})

(def ^:private reserved-keys
  "Keys the runner itself puts into a profile's build options. A suite option
   claiming one of these would be silently overwritten by the common flag that
   owns it, which is a bug a user would only find by wondering why their value
   never arrived."
  #{:nemesis :time-limit :concurrency})

(def ^:private reserved-key-options
  {:nemesis "--fault", :time-limit "--time-limit", :concurrency "--concurrency"})

(defn- invalid!
  [message data]
  (throw (ex-info message (assoc data :lite/error :invalid-suite-runner-input))))

(defn- display-name [x]
  (if (keyword? x) (name x) (str x)))

(defn- comma-list [xs]
  (str/join ", " (map display-name xs)))

(defn validate-suite!
  "Validates `suite` and returns it. No target is built and no run is started."
  [{:keys [name workloads profiles default-profile default-workloads options]
    :as suite}]
  (when-not (or (string? name) (keyword? name) (symbol? name))
    (invalid! "A suite needs a string, keyword, or symbol :name."
              {:field :name, :value name}))
  (when-not (and (sequential? workloads)
                 (seq workloads)
                 (every? keyword? workloads)
                 (= (count workloads) (count (distinct workloads))))
    (invalid! "A suite's :workloads must be a non-empty sequence of unique keywords."
              {:field :workloads, :value workloads}))
  (when-not (and (map? profiles) (seq profiles))
    (invalid! "A suite needs a non-empty map of :profiles."
              {:field :profiles, :value profiles}))
  (doseq [[profile spec] profiles]
    (when-not (keyword? profile)
      (invalid! "Every profile name must be a keyword."
                {:field :profiles, :profile profile}))
    (when-not (fn? (:build spec))
      (invalid! (str "Profile " (pr-str profile)
                     " needs a :build function of [workload opts].")
                {:field :profiles, :profile profile, :value spec}))
    (when-let [target-type (:target-type spec)]
      (when-not (contains? nemesis/validity target-type)
        (invalid! (str "Profile " (pr-str profile) " has an unknown "
                       ":target-type " (pr-str target-type) ".\n\n"
                       "Available target-types: "
                       (comma-list (sort (keys nemesis/validity))))
                  {:field :profiles, :profile profile, :value target-type}))))
  (when-not (contains? profiles default-profile)
    (invalid! (str "The suite's :default-profile " (pr-str default-profile)
                   " is not present in :profiles.")
              {:field :default-profile
               :value default-profile
               :profiles (keys profiles)}))
  (let [defaults (cond
                   (nil? default-workloads) []
                   (= :all default-workloads) workloads
                   (keyword? default-workloads) [default-workloads]
                   (and (sequential? default-workloads)
                        (seq default-workloads)) default-workloads
                   :else ::invalid)]
    (when (or (= ::invalid defaults)
              (some (complement (set workloads)) defaults))
      (invalid!
       "A suite's :default-workloads must be :all, a workload keyword, or a non-empty sequence of declared workloads."
       {:field :default-workloads
        :value default-workloads
        :workloads workloads})))
  (when-not (or (nil? options) (map? options))
    (invalid! "A suite's :options must be a map."
              {:field :options, :value options}))
  (doseq [[option spec] options]
    (when-not (keyword? option)
      (invalid! "Every suite option name must be a keyword."
                {:field :options, :option option}))
    (when-not (map? spec)
      (invalid! (str "Suite option " (pr-str option) " must have a map spec.")
                {:field :options, :option option, :value spec}))
    (when (and (contains? spec :parse) (not (fn? (:parse spec))))
      (invalid! (str "Suite option " (pr-str option)
                     " has a :parse value that is not a function.")
                {:field :options, :option option, :value (:parse spec)}))
    (when (and (contains? spec :key) (not (keyword? (:key spec))))
      (invalid! (str "Suite option " (pr-str option)
                     " has a :key value that is not a keyword.")
                {:field :options, :option option, :value (:key spec)}))
    (when (contains? reserved-keys (or (:key spec) option))
      (let [key (or (:key spec) option)]
        (invalid! (str "Suite option " (pr-str option)
                       " would be passed to the profile as " (pr-str key)
                       ", which the runner already fills in from "
                       (get reserved-key-options key) ".\n\n"
                       "  fix: give the option a different :key.")
                  {:field :options, :option option, :value (:key spec)})))
    (let [long-name (or (:long spec)
                        (str "--" (clojure.core/name option)))]
      (when-not (and (string? long-name) (str/starts-with? long-name "--"))
        (invalid! (str "Suite option " (pr-str option)
                       " has an invalid :long name.")
                  {:field :options, :option option, :value long-name}))
      (when (reserved-options long-name)
        (invalid! (str "Suite option " (pr-str option) " uses reserved option "
                       long-name ".")
                  {:field :options, :option option, :value long-name}))))
  (let [long-names (map (fn [[option spec]]
                          (or (:long spec)
                              (str "--" (clojure.core/name option))))
                        options)]
    (when-not (= (count long-names) (count (distinct long-names)))
      (invalid! "Every suite option must have a unique command-line name."
                {:field :options, :values long-names})))
  suite)

(defn- by-name
  [kind choices value]
  (or (some #(when (= value (name %)) %) choices)
      (invalid! (str "Unknown " kind " " (pr-str value) ".\n\n"
                     "Available " kind "s: " (comma-list choices))
                {:kind kind, :value value, :available choices})))

(defn- parse-long!
  [option value]
  (let [n (parse-long value)]
    (when-not (and n (pos? n))
      (invalid! (str option " needs a positive integer, got "
                     (pr-str value) ".")
                {:option option, :value value}))
    n))

(defn- option-table
  [suite]
  (into {}
        (map (fn [[option spec]]
               [(or (:long spec) (str "--" (name option)))
                [option spec]]))
        (:options suite)))

(defn- parse-custom-value
  [option spec value]
  (let [allowed (:values spec)]
    (when (and allowed (not (some #{value} allowed)))
      (invalid! (str "Invalid value " (pr-str value) " for --" (name option)
                     ".\n\nAvailable values: " (comma-list allowed))
                {:option option, :value value, :available allowed}))
    (try
      ((or (:parse spec) identity) value)
      (catch Throwable t
        (invalid! (str "Could not parse " (pr-str value) " for --"
                       (name option) ": " (ex-message t))
                  {:option option, :value value})))))

(defn- default-custom-options
  [suite]
  (reduce-kv
   (fn [parsed option spec]
     (if (contains? spec :default)
       (let [value (:default spec)]
         (assoc parsed (or (:key spec) option)
                ;; A string default is what the command line would have
                ;; supplied, so it goes through the same checking and parsing.
                ;; Anything else is already the value the suite meant, and
                ;; stringifying it would quietly turn `true` into "true".
                (if (string? value)
                  (parse-custom-value option spec value)
                  value)))
       parsed))
   {}
   (:options suite)))

(defn- need-value
  [option more]
  (or (first more)
      (invalid! (str option " needs a value.")
                {:option option})))

(defn- default-workloads
  [{:keys [workloads default-workloads]}]
  (cond
    (= :all default-workloads) workloads
    (keyword? default-workloads) [default-workloads]
    (sequential? default-workloads) (vec default-workloads)
    :else [(first workloads)]))

(defn parse-args
  "Parses the common CLI plus a suite's declared `:options`.

   Returns a run request. It does not build a target or invoke `lite.core/run`."
  [suite args]
  (let [suite        (validate-suite! suite)
        custom-table (option-table suite)
        initial      {:action       :run
                      :profile      (:default-profile suite)
                      :workloads    []
                      :all?         false
                      :faults       []
                      :time-limit   nil
                      :concurrency  nil
                      :options      (default-custom-options suite)}
        parsed
        (loop [remaining (seq args)
               parsed   initial]
          (if-not remaining
            parsed
            (let [arg  (first remaining)
                  more (next remaining)]
              (cond
                (= "--help" arg)
                (recur more (assoc parsed :action :help))

                (= "--list" arg)
                (recur more (assoc parsed :action :list))

                (= "--all-workloads" arg)
                (recur more (assoc parsed :all? true))

                (= "--workload" arg)
                (let [value (need-value arg more)]
                  (recur (next more)
                         (update parsed :workloads conj
                                 (by-name "workload" (:workloads suite) value))))

                (= "--profile" arg)
                (let [value (need-value arg more)]
                  (recur (next more)
                         (assoc parsed :profile
                                (by-name "profile" (keys (:profiles suite))
                                         value))))

                (= "--fault" arg)
                (let [value (need-value arg more)]
                  (recur (next more)
                         (update parsed :faults conj
                                 (by-name "fault" faults value))))

                (= "--time-limit" arg)
                (let [value (need-value arg more)]
                  (recur (next more)
                         (assoc parsed :time-limit
                                (parse-long! arg value))))

                (= "--concurrency" arg)
                (let [value (need-value arg more)]
                  (recur (next more)
                         (assoc parsed :concurrency
                                (parse-long! arg value))))

                (contains? custom-table arg)
                (let [value         (need-value arg more)
                      [option spec] (get custom-table arg)]
                  (recur (next more)
                         (assoc-in parsed [:options (or (:key spec) option)]
                                   (parse-custom-value option spec value))))

                :else
                (invalid!
                 (str "Unknown argument " (pr-str arg) ".\n\n"
                      "Use --help to see the suite's command line.")
                 {:argument arg})))))
        selected (cond
                   (and (:all? parsed) (seq (:workloads parsed)))
                   (invalid! "--all-workloads cannot be combined with --workload."
                             {:workloads (:workloads parsed)})

                   (:all? parsed)
                   (:workloads suite)

                   (seq (:workloads parsed))
                   (:workloads parsed)

                   :else
                   (default-workloads suite))]
    (-> parsed
        (assoc :workloads (vec (distinct selected)))
        (update :faults #(vec (distinct %)))
        (dissoc :all?))))

(defn- suite-title [suite]
  (display-name (:name suite)))

(defn- profile-faults
  "The faults a profile can take, or nil if it hasn't said which target-type
   it deploys."
  [suite profile]
  (when-let [target-type (get-in suite [:profiles profile :target-type])]
    (->> (get nemesis/validity target-type)
         (keep (fn [[fault ok?]] (when ok? fault)))
         sort
         vec)))

(defn help
  "Returns the generated help text for `suite`."
  [suite]
  (let [suite (validate-suite! suite)]
    (str
     "Run the " (suite-title suite) " Jepsen Lite suite.\n\n"
     "  --workload NAME       workload to run; may be repeated\n"
     "  --all-workloads       run every workload in the suite\n"
     "  --profile NAME        how the target is deployed\n"
     "  --fault NAME          crash, pause, partition, or power-off; may repeat\n"
     "  --time-limit SECONDS  positive integer run duration\n"
     "  --concurrency N       positive integer worker count\n"
     (apply str
            (for [[option spec] (:options suite)
                  :let [long-name (or (:long spec)
                                      (str "--" (name option)))]]
              (format "  %-21s %s%s\n"
                      (str long-name " VALUE")
                      (or (:doc spec) "suite-specific option")
                      (if-let [values (:values spec)]
                        (str " (" (comma-list values) ")")
                        ""))))
     "  --list                list workloads and profiles\n"
     "  --help                show this help\n\n"
     "Workloads: " (comma-list (:workloads suite)) "\n"
     "Profiles:  " (comma-list (keys (:profiles suite))) "\n"
     ;; Which faults a profile can take depends on how it deploys the target,
     ;; so a profile that says which target-type it is gets to advertise them.
     ;; Offering every fault to every profile invites asking for one that can
     ;; never work.
     (apply str
            (for [profile (keys (:profiles suite))
                  :let [possible (profile-faults suite profile)]
                  :when possible]
              (format "  %-16s faults: %s\n"
                      (display-name profile)
                      (if (seq possible) (comma-list possible) "none"))))
     "Default profile: " (display-name (:default-profile suite)) "\n")))

(defn listing
  "Returns the short capability listing for `suite`."
  [suite]
  (let [suite (validate-suite! suite)]
    (str "Suite: " (suite-title suite) "\n"
         "Workloads: " (comma-list (:workloads suite)) "\n"
         "Profiles: " (comma-list (keys (:profiles suite))) "\n"
         "Faults: " (comma-list faults) "\n")))

(defn- build-options
  [{:keys [faults time-limit concurrency options]}]
  (cond-> options
    (seq faults) (assoc :nemesis faults)
    time-limit   (assoc :time-limit time-limit)
    concurrency  (assoc :concurrency concurrency)))

(defn- check-faults!
  "Refuses a fault the chosen profile cannot inject, before anything runs.

   A profile that declares its `:target-type` gets this answer here rather than
   from the middle of the first run. Without one, `lite.core/run` still refuses
   the combination -- just later, and after a banner has been printed."
  [suite {:keys [profile faults]}]
  (when-let [target-type (get-in suite [:profiles profile :target-type])]
    (doseq [fault faults]
      (when-not (get-in nemesis/validity [target-type fault])
        ;; Lite's own wording, so a user meets one explanation of a fault's
        ;; limits and not two.
        (nemesis/validate! target-type [fault])))))

(defn- completed-faults
  "Fault intents whose nemesis ops completed successfully.

   An invocation alone proves only that Jepsen scheduled a fault. `:resume` is
   cleanup for `:pause`, not evidence that a pause happened."
  [history]
  (let [fault-set (set faults)]
    (into #{}
          (keep (fn [op]
                  (when (and (= :nemesis (:process op))
                             (#{:ok :info} (:type op))
                             (contains? fault-set (:f op)))
                    (:f op))))
          history)))

(defn- check-faults-happened!
  "A run that was asked for faults and produced none did not test what it
   says it tested.

   The usual cause is a profile's `:build` dropping the options it was handed,
   so `:nemesis` never reaches `lite.core/run`. The verdict is then a green
   result for a fault nobody injected, which is worse than a failure."
  [{:keys [faults]} workload result]
  (when (and (seq faults)
             ;; Only judge a run that produced a history to judge. A real one
             ;; always does; anything else is a caller standing in for
             ;; `lite.core/run`, and silence is the honest answer there.
             (contains? result :history))
    (let [completed (completed-faults (:history result))
          missing   (vec (remove completed faults))]
      (when (seq missing)
        (invalid!
         (str "Asked for " (comma-list faults) " on " (name workload)
              ", but "
              (if (= (count missing) (count faults))
                "no fault was injected"
                (str (comma-list missing) " was not injected"))
              ".\n\n"
              "  why: the run's history has no completed nemesis operation for "
              (comma-list missing) ", so this verdict says nothing about "
              (comma-list missing) ".\n\n"
              "  fix: check that the profile's :build passes its `opts` through "
              "to the run config -- `:nemesis` in particular. A build that "
              "ignores its second argument silently drops every fault asked "
              "for on the command line.")
         {:workload workload, :faults faults, :missing-faults missing})))))

(defn- run-one
  [suite request workload]
  (let [profile (:profile request)
        build   (get-in suite [:profiles profile :build])
        opts    (build-options request)]
    (println (str "\n==== " (name workload)
                  " (" (name profile) ")"
                  (when (seq (:faults request))
                    (str " " (str/join " " (map name (:faults request)))))
                  " ===="))
    (let [result (core/run (build workload opts))]
      (check-faults-happened! request workload result)
      {:profile  profile
       :workload workload
       :faults   (:faults request)
       :valid?   (:valid? result)
       :result   result})))

(defn- print-summary!
  [suite rows]
  (println (str "\n==== " (suite-title suite) " summary ===="))
  (doseq [{:keys [profile workload faults valid?]} rows]
    (println
     (format "  %-12s %-10s %-24s :valid? %s"
             (name profile)
             (name workload)
             (if (seq faults) (str/join "," (map name faults)) "-")
             (pr-str valid?))))
  (doseq [{:keys [workload error]} rows
          :when error]
    (println (str "\n  " (name workload) " did not finish:\n\n"
                  (str/replace error #"(?m)^" "    ")))))

(defn run-suite
  "Parses `args`, runs the selected suite entries, prints their summary, and
   returns `{:exit-code 0|1, :runs [...]}`. It never calls `System/exit`, which
   keeps it usable from a REPL and straightforward to test."
  [suite args]
  (let [suite   (validate-suite! suite)
        request (parse-args suite args)]
    (case (:action request)
      :help (do (print (help suite))
                {:exit-code 0, :action :help, :runs []})
      :list (do (print (listing suite))
                {:exit-code 0, :action :list, :runs []})
      :run  (do
              ;; Everything that can be known before running is settled first,
              ;; so an impossible combination costs nothing and a long suite
              ;; doesn't discover it on its last workload.
              (check-faults! suite request)
              (let [rows (mapv (fn [workload]
                                 ;; One workload failing is not a reason to
                                 ;; throw away the results of the ones that
                                 ;; already ran -- an eight-workload suite
                                 ;; shouldn't lose seven verdicts because a
                                 ;; target wouldn't start for the eighth.
                                 (try
                                   (run-one suite request workload)
                                   (catch Exception t
                                     {:profile  (:profile request)
                                      :workload workload
                                      :faults   (:faults request)
                                      :valid?   :error
                                      :error    (ex-message t)
                                      :thrown   t})))
                               (:workloads request))]
                (print-summary! suite rows)
                {:exit-code (if (every? (comp true? :valid?) rows) 0 1)
                 :action    :run
                 :runs      rows})))))

(defn resolve-suite
  "Requires and dereferences a suite var named by `suite-ref`, such as
   `sqlite.runner/suite`."
  [suite-ref]
  (let [sym (symbol suite-ref)]
    (when-not (namespace sym)
      (invalid! (str "Suite reference " (pr-str suite-ref)
                     " must be namespace-qualified, for example "
                     "sqlite.runner/suite.")
                {:suite-ref suite-ref}))
    (try
      (if-let [suite-var (requiring-resolve sym)]
        (validate-suite! @suite-var)
        (invalid! (str "Could not resolve suite var " suite-ref ".")
                  {:suite-ref suite-ref}))
      (catch java.io.FileNotFoundException _
        (invalid! (str "Could not load the namespace for suite " suite-ref ".")
                  {:suite-ref suite-ref})))))

(defn -main
  "Runs a suite var through the common CLI:

     clojure -M -m lite.runner my.target/suite [options]"
  [& args]
  (let [suite-ref (first args)]
    (try
      (when-not suite-ref
        (invalid! "The first argument must name a suite var, for example sqlite.runner/suite."
                  {}))
      (let [{:keys [exit-code]} (run-suite (resolve-suite suite-ref)
                                           (next args))]
        (shutdown-agents)
        (flush)
        (System/exit exit-code))
      (catch clojure.lang.ExceptionInfo e
        (if (:lite/error (ex-data e))
          (do
            (binding [*out* *err*]
              (println (str "\n" (ex-message e)))
              (flush))
            (shutdown-agents)
            (System/exit 2))
          (throw e))))))

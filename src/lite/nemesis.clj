(ns lite.nemesis
  "Faults, the third axis. A user names an *intent* -- `:crash`, `:pause`,
   `:partition` -- and Lite picks the implementation that fits the target-type.
   Which faults are possible is a property of how the target is deployed, not of
   the workload or of the target's protocol.

   Nemesis code never branches on the workload, and workloads never know a
   nemesis exists. The nemesis perturbs the target; the checkers simply observe
   what follows."
  (:require [clojure.string :as str]
            [jepsen.generator :as gen]
            [jepsen.nemesis :as jepsen.nemesis]
            [lite.target.compose :as compose]
            [lite.target.in-process :as in-process]
            [lite.target.local-process :as local-process]))

(def validity
  "Which faults each target-type can inject. This table is the whole of Jepsen
   Lite's static validation, and the only authority on the question -- later
   target-types enable their row here, and nothing else needs to change."
  {:http          {:crash false, :pause false, :partition false}
   :in-process    {:crash true,  :pause false, :partition false}
   :local-process {:crash true,  :pause true,  :partition false}
   :compose       {:crash true,  :pause true,  :partition true}})

(def intents
  "Every fault a user can ask for."
  [:crash :pause :partition])

(def ^:private limits
  "Why a target-type can't do more than it can."
  {:http
   (str "an :http target is something Lite talks to but doesn't run, so Lite "
        "has no way to stop it, pause it, or cut its network.")

   :in-process
   (str "an :in-process target runs inside Lite's own JVM, so there is no "
        "separate process to signal and no network link between Lite and the "
        "target to cut. The one fault it can simulate is a crash: destroying "
        "the instance and creating a new one.")

   :local-process
   (str "a :local-process target runs as a process on this machine, so it can "
        "be killed and paused, but it shares the machine's loopback with Lite "
        "and there is no network to partition.")

   :compose
   "a :compose target supports every fault."})

(defn- allowed
  "The intents this target-type can inject."
  [target-type]
  (->> (get validity target-type)
       (keep (fn [[intent ok?]] (when ok? intent)))
       sort
       vec))

(defn- targets-supporting
  "The target-types that can inject this intent."
  [intent]
  (->> validity
       (keep (fn [[target-type row]] (when (get row intent) target-type)))
       sort
       vec))

(defn- rejection
  "What went wrong, why, and what to do instead."
  [target-type intent]
  (let [ok        (allowed target-type)
        elsewhere (remove #{target-type} (targets-supporting intent))]
    (str target-type " targets can't inject " intent ".\n\n"
         "  why: " (get limits target-type) "\n\n"
         "  fix: "
         (if (seq ok)
           (str "ask for " (str/join " or " ok) " instead")
           "run this target-type without a nemesis")
         (when (seq elsewhere)
           (str ", or run the target as "
                (str/join " or " elsewhere) ", which can inject " intent))
         ", or drop :nemesis altogether.")))

(defn validate!
  "Checks the requested intents against the target-type, and explains itself if
   they don't fit. Run this before anything is built or opened."
  [target-type requested]
  (let [row (get validity target-type)]
    (when-not row
      (throw (ex-info (str "Unknown target-type " (pr-str target-type) ".")
                      {:lite/error :unusable-target-type
                       :target-type target-type})))
    (doseq [intent requested]
      (when-not (contains? row intent)
        (throw (ex-info (str "Unknown nemesis " (pr-str intent) ".\n\n"
                             "  fix: ask for one of " (pr-str intents) ".")
                        {:lite/error :unknown-nemesis
                         :nemesis    intent})))
      (when-not (get row intent)
        (throw (ex-info (rejection target-type intent)
                        {:lite/error  :invalid-nemesis
                         :nemesis     intent
                         :target-type target-type})))))
  requested)

(defn- crash-nemesis
  "Destroys and re-creates the target's instance on every `:crash` op."
  [target]
  (reify jepsen.nemesis/Nemesis
    (setup! [this _test] this)

    (invoke! [_this _test op]
      (case (:f op)
        ;; The value is which crash this was. Keep it a number: checkers read
        ;; every op in the history, and some are strict about op values.
        :crash (assoc op :value (in-process/crash! target))))

    (teardown! [_this _test] nil)))

(defn- crash-op
  "A function, not a bare map: a map is a generator of exactly one op, and we
   want a stream of crashes."
  [_test _ctx]
  {:type :info, :f :crash})

(defn- crash-generator
  [{:keys [crashes crash-interval] :or {crashes 5, crash-interval 1/5}}]
  (gen/limit crashes (gen/stagger crash-interval crash-op)))

;; ## :local-process
;;
;; The same two intents, against a target the operating system can be asked to
;; do something about. `crash` is SIGKILL and a restart; `pause` is SIGSTOP,
;; and the `:resume` that follows it is SIGCONT -- one intent, two ops, because
;; unlike a crash a pause has to be undone.

(defn- local-process-nemesis
  [target]
  (reify jepsen.nemesis/Nemesis
    (setup! [this _test] this)

    (invoke! [_this _test op]
      (assoc op :value (case (:f op)
                         ;; Which crash this was. Keep it a number: checkers
                         ;; read every op in the history, and some are strict
                         ;; about op values.
                         :crash  (local-process/crash! target)
                         :pause  (local-process/pause! target)
                         :resume (local-process/resume! target))))

    (teardown! [_this _test] nil)))

(defn- fault-cycle
  "The ops one round of the requested intents produces, in a fixed order.

   Deterministic rather than mixed, so a run's faults don't interleave into
   combinations nobody meant -- a crash landing between a pause and its resume
   would leave the resume aimed at a process that no longer exists."
  [intents {:keys [fault-interval pause-duration]
            :or   {fault-interval 1, pause-duration 3}}]
  (cond-> []
    (some #{:crash} intents)
    (conj (gen/sleep fault-interval) {:type :info, :f :crash})

    (some #{:pause} intents)
    (conj (gen/sleep fault-interval)
          {:type :info, :f :pause}
          ;; Long enough that ops actually meet the pause. If it were shorter
          ;; than the client's request timeout, a pause would only slow ops
          ;; down rather than leaving them indeterminate.
          (gen/sleep pause-duration)
          {:type :info, :f :resume})))

(defn- rounds
  "`faults` rounds of a fault cycle, each round applying every requested intent
   once.

   The limit is in ops, and a `gen/sleep` is an op like any other -- it is what
   the nemesis worker does for those seconds -- so a round's worth of ops
   includes its waits. Counting only the faults would silently deliver half the
   ones that were asked for."
  [round faults]
  (gen/limit (* faults (count round)) (cycle round)))

(defn- local-process-generator
  [intents {:keys [faults] :or {faults 5} :as opts}]
  (rounds (fault-cycle intents opts) faults))

;; ## :compose
;;
;; Every intent, because a container is the first thing Lite owns that has a
;; network interface of its own. Pumba does the work; `lite.target.compose`
;; knows the command lines.
;;
;; Two of the three heal themselves: `pumba pause` and `pumba netem` take a
;; duration and undo their own damage when it expires, so each is a single op
;; where `:local-process`'s pause needed a matching resume. A killed container
;; still has to be started again, and that stays Lite's job.

(defn- compose-nemesis
  [target]
  (reify jepsen.nemesis/Nemesis
    (setup! [this _test] this)

    (invoke! [_this _test op]
      (assoc op :value (case (:f op)
                         :crash     (compose/crash! target)
                         :pause     (compose/pause! target)
                         :partition (compose/partition! target))))

    (teardown! [_this _test] nil)))

(defn- compose-generator
  [intents {:keys [faults fault-interval] :or {faults 5, fault-interval 1}}]
  ;; A fixed order, so a run's faults don't land in combinations nobody asked
  ;; for. Every one of these heals itself except the crash, which `crash!`
  ;; finishes by starting the container again -- so unlike `:local-process`,
  ;; each intent is one op.
  (rounds (into [] (mapcat (fn [intent]
                             [(gen/sleep fault-interval)
                              {:type :info, :f intent}]))
                intents)
          faults))

(defn build
  "Returns `{:nemesis ..., :generator ...}` for the requested intents against
   this target, or nil if none were asked for. Intent -> implementation is
   dispatched here and nowhere else; later target-types add a branch."
  [target-type target {:keys [intents] :as opts}]
  (validate! target-type intents)
  (when (seq intents)
    (case target-type
      :in-process    {:nemesis   (crash-nemesis target)
                      :generator (crash-generator opts)}
      :local-process {:nemesis   (local-process-nemesis target)
                      :generator (local-process-generator intents opts)}
      :compose       {:nemesis   (compose-nemesis target)
                      :generator (compose-generator intents opts)})))

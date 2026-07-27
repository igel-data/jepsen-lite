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

(defn- lead-in
  "Nothing happens for one interval, so that the run has something to lose.

   `gen/stagger` spaces ops out but doesn't hold the first one back, so without
   this the opening fault lands microseconds into the run, before a single
   client op has completed. On a machine quick enough to get some ops
   acknowledged between faults, that merely wastes the first fault. On a slow
   one every op can still be in flight when the next fault arrives, and a run
   can reach its end having never acknowledged anything at all -- and a
   durability test containing no acknowledged writes passes while proving
   nothing. That is exactly how this was found: green here, green in CI, and
   meaningless in CI."
  [interval faults]
  (gen/phases (gen/sleep interval) faults))

(defn- crash-generator
  [{:keys [crashes crash-interval] :or {crashes 5, crash-interval 1/5}}]
  (lead-in crash-interval
           (gen/limit crashes (gen/stagger crash-interval crash-op))))

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

(defn- ops
  "An endless stream of one kind of fault op.

   A bare map is a generator of exactly *one* op, which is why these are built
   with `gen/repeat` -- and why the pairs below flip-flop between two streams
   rather than between two maps."
  [f]
  (gen/repeat {:type :info, :f f}))

(defn- schedule
  "Turns fault streams into the thing a nemesis actually runs.

   `gen/stagger` spaces the ops out rather than emitting sleeps between them,
   which is why `gen/limit` can be trusted here: it counts faults, and there is
   nothing else in the stream to count. (An earlier hand-rolled cycle of
   [sleep, fault, sleep, fault] made `:faults 2` deliver one.)

   The limit is a cap, not a schedule. With a `:time-limit` the run ends when
   the clock does -- `lite.core` puts the clients and the nemesis under the
   same limit -- so a short run simply gets fewer faults. Without one, the cap
   is what stops the nemesis running forever after the clients are done."
  [streams {:keys [faults fault-interval] :or {faults 5, fault-interval 1}}]
  (lead-in fault-interval
           (gen/limit faults (gen/stagger fault-interval (gen/mix streams)))))

(defn- local-process-generators
  "`:crash` restarts the target itself, so it is a single op. `:pause` has to
   be undone, so it alternates with `:resume`, and the final generator resumes
   once more at the end -- otherwise a run whose clock expires mid-pause would
   take its closing reads against a target that is still stopped.

   `resume!` is a no-op on a target that isn't paused, so the extra one at the
   end is free, and so is a crash landing between a pause and its resume."
  [intents opts]
  (let [pausing? (boolean (some #{:pause} intents))
        streams  (cond-> []
                   (some #{:crash} intents) (conj (ops :crash))
                   pausing? (conj (gen/flip-flop (ops :pause) (ops :resume))))]
    {:generator       (schedule streams opts)
     :final-generator (when pausing? {:type :info, :f :resume})}))

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

(defn- compose-generators
  "Each intent is a single op here: `pumba pause` and `pumba netem` take a
   duration and undo their own damage when it expires, and a killed container
   is restarted by `crash!` itself.

   That self-healing is also why the final generator is a wait rather than an
   op. A fault injected just before the clock runs out is still in force when
   it does, and nothing Lite can send will lift it early -- so the run waits it
   out before taking its closing reads."
  [intents {:keys [fault-duration] :or {fault-duration 5} :as opts}]
  {:generator       (schedule (mapv ops intents) opts)
   :final-generator (gen/sleep fault-duration)})

(defn build
  "Returns a nemesis package for the requested intents against this target, or
   nil if none were asked for:

     :nemesis          carries the ops out
     :generator        the faults to inject during the run
     :final-generator  what to do at the end so the run doesn't finish with a
                       fault still in force -- `lite.core` runs this after the
                       clients stop and before the workload's closing reads

   Intent -> implementation is dispatched here and nowhere else; later
   target-types add a branch."
  [target-type target {:keys [intents] :as opts}]
  (validate! target-type intents)
  (when (seq intents)
    (merge
     (case target-type
       :in-process    {:nemesis (crash-nemesis target)}
       :local-process {:nemesis (local-process-nemesis target)}
       :compose       {:nemesis (compose-nemesis target)})
     (case target-type
       ;; An in-process crash is synchronous and leaves nothing to undo.
       :in-process    {:generator (crash-generator opts)}
       :local-process (local-process-generators intents opts)
       :compose       (compose-generators intents opts)))))

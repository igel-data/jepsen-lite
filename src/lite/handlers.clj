(ns lite.handlers
  "Constructors for handlers of Lite's built-in workloads.

   They unpack Jepsen Lite ops and preserve the values each checker expects, so
   a target integration can be written in terms of ordinary target calls. A
   handler can still be written directly as `(fn [conn op] ...)` whenever the
   target needs a shape these constructors do not cover."
  (:refer-clojure :exclude [set])
  (:require [lite.client :as client]))

(defn- invalid!
  [workload message spec]
  (throw (ex-info (str "Invalid " (name workload) " handler: " message)
                  {:lite/error :invalid-handler
                   :workload workload
                   :spec spec})))

(defn- validate!
  [workload spec required]
  (when-not (map? spec)
    (invalid! workload "expected a map of operation functions." spec))
  (doseq [operation required]
    (when-not (fn? (get spec operation))
      (invalid! workload
                (str (pr-str operation) " must be a function.")
                spec)))
  spec)

(defn- unsupported!
  [workload operation]
  (throw (ex-info (str "The " (name workload) " workload has no "
                       (pr-str operation) " operation.")
                  {:lite/error :unsupported-handler-operation
                   :workload workload
                   :operation operation})))

(defn register
  "Builds a register handler from:

     :read   (fn [conn key] value)
     :write  (fn [conn key value] ...)
     :cas    (fn [conn key old new] ...)

   Write and successful CAS return the op payload required by the linearizable
   register checker. A CAS function may call `lite.client/fail!` itself, or
   return exactly false to report a mismatch."
  [spec]
  (let [{:keys [read write cas]} (validate! :register spec
                                            [:read :write :cas])]
    (fn [conn {:keys [f key value]}]
      (case f
        :read  (read conn key)
        :write (do (write conn key value) value)
        :cas   (let [[old new] value]
                 (if (false? (cas conn key old new))
                   (client/fail! {:type :cas-mismatch
                                  :key key
                                  :expected old})
                   value))
        (unsupported! :register f)))))

(defn set
  "Builds a grow-only set handler from:

     :add   (fn [conn element] ...)
     :read  (fn [conn] elements)

   The add returns the element the checker must account for. A nil read is
   normalized to an empty collection, which is the state before the first add."
  [spec]
  (let [{:keys [add read]} (validate! :set spec [:add :read])]
    (fn [conn {:keys [f value]}]
      (case f
        :add  (do (add conn value) value)
        :read (or (read conn) [])
        (unsupported! :set f)))))

(defn counter
  "Builds a counter handler from:

     :add   (fn [conn amount] ...)
     :read  (fn [conn] total)

   Adds return the attempted amount rather than the target's new total, as
   required by the checker. A nil initial read is normalized to zero."
  [spec]
  (let [{:keys [add read]} (validate! :counter spec [:add :read])]
    (fn [conn {:keys [f value]}]
      (case f
        :add  (do (add conn value) value)
        :read (long (or (read conn) 0))
        (unsupported! :counter f)))))

(defn bank
  "Builds a bank handler from:

     :init      (fn [conn balances] ...)
     :read      (fn [conn] balances)
     :transfer  (fn [conn from to amount] ...)

   Rejected transfers should call `lite.client/fail!`; indeterminate outcomes
   should call `lite.client/info!`."
  [spec]
  (let [{:keys [init read transfer]} (validate! :bank spec
                                                [:init :read :transfer])]
    (fn [conn {:keys [f value]}]
      (case f
        :init     (init conn value)
        :read     (read conn)
        :transfer (let [{:keys [from to amount]} value]
                    (transfer conn from to amount))
        (unsupported! :bank f)))))

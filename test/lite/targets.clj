(ns lite.targets
  "In-memory stand-ins for a persistent key-value store, for the tests to run
   against: one that behaves, one whose handler is wrong, and one that
   *simulates a durability bug* -- it acknowledges writes before they are
   durable, and loses them when the instance is destroyed.

   These are fixtures, not the library and not the examples -- the test suite
   stands on its own. `examples/demo/kvs.clj` shows the same ideas as something
   a user would read."
  (:require [lite.client :as client :refer [fail!]]))

;; ## The adapters
;;
;; One adapter serves every workload: it knows the calling convention of a
;; key-value store and the connection lifecycle, and nothing about which
;; workload is running or how the store is deployed. The store starts empty;
;; whatever initial state a workload needs, the workload writes for itself.
;;
;; The store is created once, when the adapter is built -- the way a persistent
;; KVS's data directory outlives any one instance of the process. `open` only
;; *attaches* to it. That is what makes a crash (close, then open) durable by
;; default, which is the honest default for a store that persists.

(defrecord Adapter [handler store]
  client/ClientAdapter
  (open [_]
    ;; Attach to the durable state. Note what is absent: any creation or
    ;; resetting of data. A crash re-enters here and finds what was committed.
    store)

  (invoke [_ conn op]
    (client/complete handler conn op))

  (close [_ _conn]
    nil))

(defrecord UnsoundAdapter [handler durable flush-every ops]
  client/ClientAdapter
  (open [_]
    ;; Recovery: whatever reached durable storage. Anything acknowledged since
    ;; the last flush is gone -- which is the bug this fixture exists to
    ;; simulate, not how a correct store behaves.
    (atom @durable))

  (invoke [_ conn op]
    (let [completed (client/complete handler conn op)]
      ;; The defect: the write is acknowledged above, and only *sometimes*
      ;; actually made durable. A target with a missing fsync, or a WAL it never
      ;; flushes, loses exactly this way.
      (when (zero? (mod (swap! ops inc) flush-every))
        (reset! durable @conn))
      completed))

  (close [_ _conn]
    nil))

(defn adapter
  "A store that persists: what it acknowledged, it keeps."
  []
  (map->Adapter {:store (atom {})}))

(defn unsound-adapter
  "A store that acknowledges writes before they are durable and loses the
   unflushed ones when it crashes. This simulates a bug in a target -- the thing
   crash testing exists to catch, not the normal behaviour of a persistent
   store."
  ([] (unsound-adapter 20))
  ([flush-every]
   (map->UnsoundAdapter {:durable     (atom {})
                         :flush-every flush-every
                         :ops         (atom 0)})))

;; ## :register -- one register per key

(defn- cas!
  "Sets k to `new` if it holds `old`. Returns true if it did."
  [kvs k old new]
  (let [[before _after] (swap-vals! kvs (fn [m]
                                          (if (= (get m k) old)
                                            (assoc m k new)
                                            m)))]
    ;; Compare on `before` rather than on whether the map changed: a CAS from a
    ;; value to itself is a success that changes nothing.
    (= (get before k) old)))

(defn- broken-cas!
  "The defect: writes `new` whichever value the register actually holds. Every
   CAS 'succeeds', including ones that should have failed, so the register takes
   on values no linearization can justify."
  [kvs k _old new]
  (swap! kvs assoc k new)
  true)

(defn- register-handler [cas-fn]
  (fn [kvs {:keys [f key value]}]
    (case f
      :read  (get @kvs key)
      :write (do (swap! kvs assoc key value) value)
      :cas   (let [[old new] value]
               (if (cas-fn kvs key old new)
                 value
                 (fail! "cas mismatch"))))))

;; ## :set -- a growing collection under one key

(defn- add! [kvs element]
  (swap! kvs update :elements (fnil conj []) element))

(defn- broken-add!
  "The defect: silently drops every fifth element. The add is acknowledged, so
   the checker counts the element as one the target promised to keep."
  [kvs element]
  (when-not (zero? (mod element 5))
    (swap! kvs update :elements (fnil conj []) element)))

(defn- set-handler [add-fn]
  (fn [kvs {:keys [f value]}]
    (case f
      :add  (do (add-fn kvs value) value)
      :read (get @kvs :elements []))))

;; ## :counter -- a monotonic integer under one key

(defn- increment! [kvs amount]
  (swap! kvs update :counter (fnil + 0) amount))

(defn- broken-increment!
  "The defect: credits only half of each increment, rounding down, so the
   counter drifts far below the sum of the increments it acknowledged."
  [kvs amount]
  (swap! kvs update :counter (fnil + 0) (quot amount 2)))

(defn- counter-handler [add-fn]
  (fn [kvs {:keys [f value]}]
    (case f
      :add  (do (add-fn kvs value) value)
      :read (long (get @kvs :counter 0)))))

;; ## :bank -- accounts whose total must never change
;;
;; The one workload that needs transactions: a transfer touches two accounts,
;; and a read that lands mid-transfer must still see the full total. The opening
;; balances arrive as an ordinary `:init` op from the workload's first phase --
;; the adapter knows nothing about them.

(defn- transfer!
  "Debits and credits in one atomic step."
  [accts {:keys [from to amount]}]
  (let [[before after]
        (swap-vals! accts (fn [m]
                            (if (<= amount (get m from 0))
                              (-> m (update from - amount) (update to + amount))
                              m)))]
    (if (= before after)
      (fail! "insufficient funds")
      amount)))

(defn- broken-transfer!
  "The defect: debits the sender and never credits the recipient, so every
   transfer destroys money and the total stops adding up."
  [accts {:keys [from amount]}]
  (let [[before after] (swap-vals! accts (fn [m]
                                           (if (<= amount (get m from 0))
                                             (update m from - amount)
                                             m)))]
    (if (= before after)
      (fail! "insufficient funds")
      amount)))

(defn- bank-handler [transfer-fn]
  (fn [accts {:keys [f value]}]
    (case f
      :init     (do (swap! accts merge value) value)
      :read     @accts
      :transfer (transfer-fn accts value))))

;; ## The fixtures

(def targets
  "Workload -> a correct handler and one with a defect. Note what isn't here:
   any initial state. The store starts empty and each workload seeds its own."
  {:register {:correct (register-handler cas!)
              :broken  (register-handler broken-cas!)}
   :set      {:correct (set-handler add!)
              :broken  (set-handler broken-add!)}
   :counter  {:correct (counter-handler increment!)
              :broken  (counter-handler broken-increment!)}
   :bank     {:correct (bank-handler transfer!)
              :broken  (bank-handler broken-transfer!)}})

(defn config
  "A run config for one workload against these fixtures. Options:

     :variant     :correct (default) or :broken -- is the handler right?
     :durability  :sound (default) or :buggy -- does the store keep what it
                  acknowledged, when it is crashed?
     :nemesis     faults to inject, e.g. [:crash]"
  ([workload] (config workload {}))
  ([workload {:keys [variant durability nemesis]
              :or   {variant :correct, durability :sound}}]
   (let [target (get targets workload)]
     (assert target (str "No target for workload " (pr-str workload)))
     (cond-> {:adapter  (case durability
                          :sound (adapter)
                          :buggy (unsound-adapter))
              :handler  (get target variant)
              :workload workload
              :name     (str "jepsen-lite-test-" (name workload))
              :target   {:type :in-process}}
       nemesis (assoc :nemesis nemesis
                      ;; An in-memory target answers in microseconds, so these
                      ;; runs are over in a few dozen milliseconds. Crash far
                      ;; more often than a real run would, or the fault would
                      ;; land after everything already happened.
                      :nemesis-opts {:crashes 8, :crash-interval 1/500})))))

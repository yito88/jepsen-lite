(ns lite.demo
  "Worked examples: for each workload, an embedded target that is correct and
   one with a deliberate defect. Running both is how we know a checker
   discriminates -- a checker that only ever passes has not been shown to work.

   Note what is *not* here: no `jepsen.client/Client`, no test map, no
   generator, no checker. A user writes an adapter and a handler, and picks a
   workload."
  (:require [lite.client :as client :refer [fail!]]
            [lite.core :as core]))

;; ## The adapter
;;
;; One adapter serves every demo: it knows the calling convention of an
;; in-memory store (an atom holding some state) and the connection lifecycle,
;; and nothing about how that store is deployed.
;;
;; The instance lives in the adapter, not in a conn: Jepsen opens one client per
;; worker process, and every worker must talk to the *same* store, the way real
;; clients share one server. So `open` returns a handle to the shared instance,
;; creating it if there isn't one, and `close` drops only the caller's handle.
;; Both stay re-runnable, as the crash nemesis will need.

(defrecord Adapter [handler init instance]
  client/ClientAdapter
  (open [_]
    (swap! instance #(or % (atom (init)))))

  (invoke [_ conn op]
    (client/complete handler conn op))

  (close [_ _conn]
    ;; Dropping a client handle leaves the instance running. Discarding the
    ;; instance itself is a fault, and belongs to the crash nemesis.
    nil))

(defn adapter [init]
  (map->Adapter {:init init, :instance (atom nil)}))

;; ## :register -- a map of keys to registers
;;
;; `:value` is the payload for one register; `:key` says which one. A CAS
;; mismatch is a *certain* failure, so it calls `fail!` -- and a history full of
;; those is still perfectly linearizable.

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

;; ## :set -- a growing collection

(defn- set-handler [add-fn]
  (fn [s {:keys [f value]}]
    (case f
      :add  (do (add-fn s value) value)
      :read (vec @s))))

(defn- add! [s element]
  (swap! s conj element))

(defn- broken-add!
  "The defect: silently drops every fifth element. The add is acknowledged, so
   the checker counts the element as one the target promised to keep."
  [s element]
  (when-not (zero? (mod element 5))
    (swap! s conj element)))

;; ## :counter -- a monotonic integer

(defn- counter-handler [add-fn]
  (fn [c {:keys [f value]}]
    (case f
      :add  (do (add-fn c value) value)
      :read (long @c))))

(defn- increment! [c amount]
  (swap! c + amount))

(defn- broken-increment!
  "The defect: credits only half of each increment, rounding down, so the
   counter drifts far below the sum of the increments it acknowledged."
  [c amount]
  (swap! c + (quot amount 2)))

;; ## :bank -- accounts whose total must never change
;;
;; The one demo that needs transactions: a transfer touches two accounts, and a
;; read that lands mid-transfer must still see the full total.

(def accounts
  "Every account starts empty except the first, which holds the lot."
  (into {0 100} (map (fn [a] [a 0])) (range 1 8)))

(defn- transfer!
  "Debits and credits in one atomic step."
  [accts {:keys [from to amount]}]
  (let [[before after]
        (swap-vals! accts (fn [m]
                            (if (<= amount (get m from))
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
                                           (if (<= amount (get m from))
                                             (update m from - amount)
                                             m)))]
    (if (= before after)
      (fail! "insufficient funds")
      amount)))

(defn- bank-handler [transfer-fn]
  (fn [accts {:keys [f value]}]
    (case f
      :read     @accts
      :transfer (transfer-fn accts value))))

;; ## The demos

(def demos
  "Workload -> how to start the target, and a correct and a broken handler."
  {:register {:init    (constantly {})
              :correct (register-handler cas!)
              :broken  (register-handler broken-cas!)}
   :set      {:init    (constantly [])
              :correct (set-handler add!)
              :broken  (set-handler broken-add!)}
   :counter  {:init    (constantly 0)
              :correct (counter-handler increment!)
              :broken  (counter-handler broken-increment!)}
   :bank     {:init    (constantly accounts)
              :correct (bank-handler transfer!)
              :broken  (bank-handler broken-transfer!)}})

(defn config
  "A run config for one workload, against either the correct target or the
   broken one."
  ([workload] (config workload :correct))
  ([workload variant]
   (let [demo (get demos workload)]
     (assert demo (str "No demo for workload " (pr-str workload)))
     {:adapter  (adapter (:init demo))
      :handler  (get demo variant)
      :workload workload
      :name     (str "jepsen-lite-demo-" (name workload))
      :target   {:type :in-process}})))

(defn -main
  "`clojure -M:run [workload] [broken]`, e.g. `clojure -M:run bank broken`.
   Defaults to the correct register."
  [& args]
  (let [args     (set args)
        broken?  (contains? args "broken")
        workload (or (first (filter (comp args name) (keys demos))) :register)
        variant  (if broken? :broken :correct)
        {:keys [valid?]} (core/run (config workload variant))]
    (println (str "\n" (name workload) " (" (name variant) "): :valid? "
                  (pr-str valid?)))
    (shutdown-agents)
    (System/exit (if (= valid? (not broken?)) 0 1))))

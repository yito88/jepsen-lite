(ns lite.demo
  "Two worked examples: an embedded key-value store that is a correct
   linearizable register, and one with a deliberate defect. Running both is how
   we know the checker discriminates -- a checker that only ever passes has not
   been shown to work.

   Note what is *not* here: no `jepsen.client/Client`, no test map, no
   generator, no checker. A user writes an adapter and a handler, and picks a
   workload."
  (:require [lite.client :as client :refer [fail!]]
            [lite.core :as core]))

;; ## The stand-in target: a map of keys to registers, behind an atom.

(defn open-kvs [] (atom {}))

(defn kvs-read [kvs k] (get @kvs k))

(defn kvs-write! [kvs k v] (swap! kvs assoc k v) v)

(defn kvs-cas!
  "Sets k to `new` if it holds `old`. Returns true if it did."
  [kvs k old new]
  (let [[before _after] (swap-vals! kvs (fn [m]
                                          (if (= (get m k) old)
                                            (assoc m k new)
                                            m)))]
    ;; Compare on `before` rather than on whether the map changed: a CAS from a
    ;; value to itself is a success that changes nothing.
    (= (get before k) old)))

(defn broken-cas!
  "The defect: writes `new` whichever value the register actually holds. Every
   CAS 'succeeds', including ones that should have failed, so the register takes
   on values no linearization can justify."
  [kvs k _old new]
  (swap! kvs assoc k new)
  true)

;; ## The adapter
;;
;; It knows the KVS's calling convention and the connection lifecycle, and
;; nothing about how the KVS is deployed.
;;
;; The KVS instance lives in the adapter, not in a conn: Jepsen opens one client
;; per worker process, and every worker must talk to the *same* store, the way
;; real clients share one server. So `open` returns a handle to the shared
;; instance, creating it if there isn't one, and `close` drops only the caller's
;; handle. Both stay re-runnable, as the crash nemesis will need.

(defrecord KvsAdapter [handler instance]
  client/ClientAdapter
  (open [_]
    (swap! instance #(or % (open-kvs))))

  (invoke [_ conn op]
    (client/complete handler conn op))

  (close [_ _conn]
    ;; Dropping a client handle leaves the instance running. Discarding the
    ;; instance itself is a fault, and belongs to the crash nemesis.
    nil))

(defn adapter []
  (map->KvsAdapter {:instance (atom nil)}))

;; ## The handlers: :register ops -> KVS calls.
;;
;; `:value` is the payload for one register; `:key` says which one. A CAS
;; mismatch is a *certain* failure, so it calls `fail!` -- and a history full of
;; those is still perfectly linearizable.

(defn- handle
  [cas! kvs {:keys [f key value]}]
  (case f
    :read  (kvs-read kvs key)
    :write (kvs-write! kvs key value)
    :cas   (let [[old new] value]
             (if (cas! kvs key old new)
               value
               (fail! "cas mismatch")))))

(def handler
  "A correct register."
  (partial handle kvs-cas!))

(def broken-handler
  "A register whose CAS ignores the compare."
  (partial handle broken-cas!))

(defn config
  ([] (config handler))
  ([handler]
   {:adapter  (adapter)
    :handler  handler
    :workload :register
    :name     "jepsen-lite-demo"
    :target   {:type :in-process}}))

(defn -main
  "`clojure -M:run` checks the correct register; `clojure -M:run broken` checks
   the one with the defect."
  [& args]
  (let [broken? (= ["broken"] args)
        {:keys [valid?]} (core/run (config (if broken? broken-handler handler)))]
    (println (str "\n" (if broken? "broken" "correct") " register: :valid? "
                  (pr-str valid?)))
    (shutdown-agents)
    (System/exit (if (= valid? (not broken?)) 0 1))))

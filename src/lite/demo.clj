(ns lite.demo
  "A tiny worked example: an embedded single-register KVS, an in-process
   ClientAdapter for it, and a handler. This stands in for a user's real
   embedded store and doubles as the M1 acceptance check.

   Note what is *not* here: no `jepsen.client/Client`, no test map, no
   generator interpreter. A user writes an adapter and a handler; Lite supplies
   the rest."
  (:require [jepsen.generator :as jgen]
            [lite.client :as client :refer [fail! info!]]
            [lite.core :as core]
            [lite.gen :as gen]))

;; ## The stand-in target: a single register backed by an atom.

(defn open-kvs [] (atom nil))

(defn kvs-read [kvs] @kvs)

(defn kvs-write! [kvs v] (reset! kvs v) v)

(defn kvs-cas!
  "Returns true if the register held `old` and now holds `new`."
  [kvs old new]
  (compare-and-set! kvs old new))

;; ## The adapter
;;
;; It knows the KVS's calling convention and the connection lifecycle, and
;; nothing about how the KVS is deployed.
;;
;; The KVS instance lives in the adapter, not in a conn: Jepsen opens one client
;; per worker process, and every worker must talk to the *same* register, the
;; way real clients share one server. So `open` returns a handle to the shared
;; instance, creating it if there isn't one, and `close` drops only the caller's
;; handle. Both stay re-runnable, as M0 requires.

(defrecord RegisterAdapter [handler instance]
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
  (map->RegisterAdapter {:instance (atom nil)}))

;; ## The handler: ops -> KVS calls.

(defn handler [kvs {:keys [f value simulate]}]
  (when (= :timeout simulate)
    (info! :timeout))
  (case f
    :read  (kvs-read kvs)
    :write (kvs-write! kvs value)
    :cas   (let [[old new] value]
             (if (kvs-cas! kvs old new)
               value
               (fail! "cas mismatch")))))

;; ## The generator
;;
;; `lite.gen`'s read/write/cas mix, plus one demo-only op that pretends to time
;; out, so the printed history shows an `:info` completion too.

(defn timeout-w
  "A write the demo target never answers."
  [_test _ctx]
  {:type :invoke, :f :write, :value (rand-int 5), :simulate :timeout})

(def generator
  (->> (jgen/mix [gen/r gen/w gen/cas timeout-w])
       (jgen/stagger 1/1000)
       (jgen/limit 32)
       jgen/clients))

(def config
  {:adapter   (adapter)
   :handler   handler
   :generator generator
   :name      "jepsen-lite-demo"
   :target    {:type :in-process}})

(defn -main [& _args]
  (core/run config)
  (shutdown-agents))

(ns lite.demo
  "A tiny worked example: an embedded single-register KVS, an in-process
   ClientAdapter for it, and a handler. This stands in for a user's real
   embedded store and doubles as the M0 acceptance check."
  (:require [lite.client :as client :refer [fail! info!]]
            [lite.core :as core]))

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

(defrecord RegisterAdapter [handler]
  client/ClientAdapter
  (open [_]
    (open-kvs))

  (invoke [_ conn op]
    (client/complete handler conn op))

  (close [_ _conn]
    ;; The instance is garbage once the conn handle is dropped; nothing to do,
    ;; and safe to call again.
    nil))

(defn adapter [] (map->RegisterAdapter {}))

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

(def config
  {:adapter (adapter)
   :handler handler
   :ops     [{:f :write, :value 1}
             {:f :read}
             {:f :cas,   :value [1 2]}       ; matches -> :ok
             {:f :cas,   :value [1 3]}       ; mismatches -> :fail
             {:f :write, :value 4, :simulate :timeout} ; -> :info
             {:f :read}]
   :target  {:type :in-process}})

(defn -main [& _args]
  (core/run config)
  (shutdown-agents))

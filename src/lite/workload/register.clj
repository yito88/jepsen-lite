(ns lite.workload.register
  "The `:register` workload: independent linearizable registers.

   Generators come from `jepsen.tests.linearizable-register`; the verdict comes
   from Knossos via `jepsen.checker/linearizable`. Lite supplies neither -- it
   only wires them to the user's handler.

   ## Handler contract

   Ops arrive with `:f` and a `:value` payload for one logical register:

     :read   value is nil        -> return the register's current value
     :write  value is v          -> set the register to v; return normally
     :cas    value is [old new]  -> if the register holds `old`, set it to
                                    `new`; otherwise call `lite.client/fail!`

   The workload runs many independent registers so each checked history stays
   short (linearizability checking is NP-hard). Which register an op addresses
   is on the op as `:key`; a target that holds a single register can ignore it."
  (:require [jepsen.checker :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.client :as jepsen.client]
            [jepsen.generator :as gen]
            [jepsen.independent :as independent]
            [jepsen.tests.linearizable-register :as lr]
            [knossos.model :as model]))

(defrecord PerKeyClient [client]
  jepsen.client/Client
  (open! [this test node]
    (assoc this :client (jepsen.client/open! client test node)))

  (setup! [this test]
    (assoc this :client (jepsen.client/setup! client test)))

  (invoke! [_this test op]
    ;; The workload's ops carry `[key payload]` tuples. Hand the handler the
    ;; payload alone -- it writes for one register -- and put the tuple back on
    ;; the completion, which is what the independent checker splits on.
    (let [[k v] (:value op)
          op'   (jepsen.client/invoke! client test (assoc op :value v, :key k))]
      (assoc op' :value (independent/tuple k (:value op')))))

  (teardown! [_this test]
    (jepsen.client/teardown! client test))

  (close! [_this test]
    (jepsen.client/close! client test)))

(defn workload
  "Options:

     :nodes          The run's nodes; only the count matters -- the workload
                     gives each key 2 threads per node.
     :per-key-limit  Max ops per register (default 20). Keep this small: it
                     bounds the length of each history Knossos must check.
     :process-limit  Max processes touching one register (default 10).
     :op-limit       Total ops, which is what ends the run (default 100); the
                     underlying generator walks an unbounded key sequence."
  [{:keys [nodes per-key-limit process-limit op-limit]
    :or   {per-key-limit 20, process-limit 10, op-limit 100}}]
  {:generator   (gen/limit op-limit
                           (:generator (lr/test {:nodes         nodes
                                                 :per-key-limit per-key-limit
                                                 :process-limit process-limit})))
   :checker     (independent/checker
                 (checker/compose
                  {:linearizable (checker/linearizable
                                  {:model     (model/cas-register)
                                   :algorithm :linear})
                   :timeline     (timeline/html)}))
   :wrap-client ->PerKeyClient
   ;; Keys are worked on by groups of 2 threads per node, and the generator
   ;; insists the worker count divide evenly into groups.
   :concurrency (* 2 (count nodes))})

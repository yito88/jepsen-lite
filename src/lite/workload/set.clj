(ns lite.workload.set
  "The `:set` workload: add distinct elements, then read them all back.

   The lightest workload -- it asks nothing of the target beyond adds and a
   read, and catches lost writes and phantom elements.

   ## Handler contract

     :add   value is one element -> add it to the collection; return normally
     :read  value is nil        -> return the whole current collection

   The shape matters: a long run of adds, then a single final read. The checker
   compares that read against the adds it acknowledged."
  (:require [jepsen.checker :as checker]
            [jepsen.generator :as gen]))

(defn adds
  "An unbounded stream of adds of distinct elements."
  []
  (map (fn [x] {:f :add, :value x}) (range)))

(defn workload
  "Options:

     :op-limit  How many adds to attempt (default 200), or false for as many
                as the run has time for."
  [{:keys [op-limit] :or {op-limit 200}}]
  {:generator (gen/clients (cond->> (adds)
                             op-limit (gen/limit op-limit)))
   ;; The read runs after the adds are done, and after any time limit has
   ;; expired: without it there is nothing to check the adds against.
   :final-generator (gen/clients (gen/once {:f :read}))
   :checker         (checker/set)
   :concurrency     4})

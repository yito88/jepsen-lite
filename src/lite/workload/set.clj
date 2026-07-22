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

     :op-limit  How many adds to attempt (default 200)."
  [{:keys [op-limit] :or {op-limit 200}}]
  {:generator   (gen/phases (gen/clients (gen/limit op-limit (adds)))
                            (gen/clients (gen/once {:f :read})))
   :checker     (checker/set)
   :concurrency 4})

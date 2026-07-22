(ns lite.workload.counter
  "The `:counter` workload: increment a counter and read it back.

   Deliberately the weak one. The checker only verifies each read falls between
   the sum of acknowledged increments and the sum of attempted ones, so it
   catches gross errors -- lost or duplicated increments -- and proves little
   else.

   ## Handler contract

     :add   value is a positive integer -> increment the counter by it
     :read  value is nil                -> return the counter's current value

   Reads must return an integer, and the counter only ever goes up."
  (:require [jepsen.checker :as checker]
            [jepsen.generator :as gen]))

(defn add [_test _ctx] {:f :add, :value (long (inc (rand-int 4)))})

(defn r [_test _ctx] {:f :read})

(defn workload
  "Options:

     :op-limit  Total ops (default 200)."
  [{:keys [op-limit] :or {op-limit 200}}]
  {:generator   (gen/clients (gen/limit op-limit (gen/mix [add add r])))
   :checker     (checker/counter)
   :concurrency 4})

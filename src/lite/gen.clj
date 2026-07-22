(ns lite.gen
  "Op constructors and a default generator.

   This is deliberately thin: M1 only needs enough op supply to exercise the
   generator -> adapter -> history pipeline. Real, selectable workloads
   (register / set / bank / counter) arrive later and will supply their own
   generators.

   Constructors set only `:type :invoke`, `:f` and `:value`; `:process`,
   `:time` and `:index` are assigned by Jepsen's interpreter."
  (:require [jepsen.generator :as gen]))

(defn r
  "A read of the register."
  [_test _ctx]
  {:type :invoke, :f :read, :value nil})

(defn w
  "A write of a small random value."
  [_test _ctx]
  {:type :invoke, :f :write, :value (rand-int 5)})

(defn cas
  "A compare-and-set of one small random value to another. Often mismatches,
   which is the point: it exercises the `:fail` path."
  [_test _ctx]
  {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(def default
  "A read/write/cas mix over a single register, bounded so a run terminates."
  (->> (gen/mix [r w cas])
       (gen/stagger 1/1000)
       (gen/limit 30)
       gen/clients))

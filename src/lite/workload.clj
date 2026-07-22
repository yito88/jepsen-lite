(ns lite.workload
  "Workloads: what ops to run, and what counts as correct.

   A workload is a map:

     :generator    the op stream
     :checker      the verdict over the resulting history
     :wrap-client  optional (fn [client] client'), for workloads whose ops need
                   translating before the user's handler sees them
     :test-opts    optional extra test-map entries the workload's own generator
                   or checker reads (bank's accounts and starting total)
     :concurrency  how many workers this workload wants, if it cares

   Each workload also documents the handler contract it expects -- which `:f`
   values arrive, and what the handler should return -- in its own namespace.

   Workloads know nothing about target-type or faults; those are separate axes."
  (:require [lite.workload.bank :as bank]
            [lite.workload.counter :as counter]
            [lite.workload.register :as register]
            [lite.workload.set :as set]))

(def workloads
  "Workload name -> (fn [opts] workload-map)."
  {:register register/workload
   :set      set/workload
   :bank     bank/workload
   :counter  counter/workload})

(defn build
  "Constructs the named workload."
  [workload-name opts]
  (if-let [f (get workloads workload-name)]
    (f opts)
    (throw (ex-info (str "Unknown workload " (pr-str workload-name))
                    {:workload workload-name
                     :known    (sort (keys workloads))}))))

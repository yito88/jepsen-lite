(ns lite.core
  "The runner: config in, verdict out.

   A run is Jepsen's own pipeline with the parts a Lite user shouldn't have to
   see kept internal -- generator -> the user's adapter (via the bridge) ->
   history -> the workload's checker."
  (:require [clojure.pprint :as pprint]
            [jepsen.core :as jepsen]
            [jepsen.generator :as gen]
            [jepsen.generator.interpreter :as interpreter]
            [jepsen.history :as h]
            [jepsen.store :as store]
            [jepsen.tests :as tests]
            [jepsen.util :as util]
            [lite.bridge :as bridge]
            [lite.nemesis :as nemesis]
            [lite.target]
            [lite.target.in-process]
            [lite.workload :as workload]))

(def default-nodes
  "An in-process target is one logical node. Later target-types supply their
   own; nothing here branches on which."
  ["local"])

(def default-target
  {:type :in-process})

(defn validate!
  "Checks the combinations a run can't recover from -- an unrunnable
   target-type, or a fault that target-type can't inject -- before anything is
   built, opened or generated. Throws with an explanation; returns config."
  [{:keys [target nemesis]}]
  (let [target (or target default-target)]
    (lite.target/validate! target)
    (nemesis/validate! (:type target) nemesis))
  nil)

(defn test-map
  "Builds the Jepsen test map for `config`. Everything not named here keeps
   `noop-test`'s defaults: a noop os/db is correct for an in-process target,
   which has no node to configure."
  [{:keys [adapter handler workload workload-opts concurrency name nodes
           target nemesis nemesis-opts]}]
  (let [nodes   (or nodes default-nodes)
        target  (or target default-target)
        w       (workload/build (or workload :register)
                                (assoc workload-opts :nodes nodes))
        ;; `invoke` takes no handler argument, so the adapter carries it. The
        ;; config is the user-facing place to put it; bind it in here.
        adapter (cond-> adapter handler (assoc :handler handler))
        conn    (lite.target/build target adapter)
        nem     (nemesis/build (:type target) conn
                               (assoc nemesis-opts :intents nemesis))]
    (merge tests/noop-test
           ;; Whatever the workload's own generator and checker need to find in
           ;; the test map.
           (:test-opts w)
           {:pure-generators true
            :name            (or name "jepsen-lite")
            :nodes           nodes
            :concurrency     (or concurrency (:concurrency w) (count nodes))
            :client          (cond-> (bridge/client adapter conn)
                               (:wrap-client w) ((:wrap-client w)))
            :generator       (if nem
                               (gen/nemesis (:generator nem) (:generator w))
                               (:generator w))
            :checker         (:checker w)}
           (when nem {:nemesis (:nemesis nem)}))))

(defn- realize-history
  "The history `with-history!` hands back reads its chunks from the store file
   on demand, so it dies once the store handle closes. Pull it into memory while
   the handle is still open, as an equivalent in-memory history."
  [history]
  (h/history (into [] history)
             {:already-ops? true, :have-indices? true, :dense-indices? true}))

(defn run
  "Runs `config`:

     {:adapter       <a ClientAdapter>
      :handler       (fn [conn op] ...) ; the user's op -> target-call mapping
      :workload      :register          ; optional; :register is the default
      :workload-opts {...}              ; optional; see the workload's ns
      :concurrency   <n>                ; optional; the workload picks otherwise
      :name          \"...\"            ; optional
      :target        {:type :in-process} ; optional; :in-process is the default
      :nemesis       [:crash]           ; optional; faults to inject
      :nemesis-opts  {...}}             ; optional; :crashes, :crash-interval

   Interprets the workload's generator against the user's adapter -- with the
   nemesis, if any, perturbing the target as it goes -- checks the resulting
   history, and returns `{:valid? ..., :results ..., :history ...}`. The verdict
   is printed and, like the history, written to `store/`.

   `prepare-test` supplies the `:start-time`/`:concurrency`/`:barrier` the
   interpreter needs and makes the generator forgettable; the store handle has
   to be open for the interpreter's history writer, and stays open through
   analysis so the checker's results land next to the history."
  [config]
  (validate! config)
  (let [test (jepsen/prepare-test (test-map config))
        test (store/with-handle [test test]
               ;; save-0! writes the initial test; the history writer refuses to
               ;; open without the block id it leaves in the test's metadata.
               (let [test (store/save-0! test)
                     test (util/with-relative-time
                            (store/with-history! [test test]
                              (interpreter/run! test)))]
                 (-> test
                     (update :history realize-history)
                     store/save-1!
                     ;; Runs the checker and writes the results out.
                     jepsen/analyze!)))
        results (:results test)]
    (println "\nVerdict:")
    (pprint/pprint results)
    {:valid?  (:valid? results)
     :results results
     :history (:history test)}))

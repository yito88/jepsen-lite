(ns lite.core
  "The runner. M0's hand-rolled loop over a hardcoded op list is replaced here
   by Jepsen's generator interpreter, which supplies ops, spawns workers, and
   records a real `jepsen.history`. No checker runs yet."
  (:require [clojure.pprint :as pprint]
            [jepsen.core :as jepsen]
            [jepsen.generator.interpreter :as interpreter]
            [jepsen.history :as h]
            [jepsen.store :as store]
            [jepsen.tests :as tests]
            [jepsen.util :as util]
            [lite.bridge :as bridge]
            [lite.gen :as gen]))

(defn test-map
  "Builds the Jepsen test map for `config`. Everything not named here keeps
   `noop-test`'s defaults: a noop os/db/nemesis/checker is correct for an
   in-process target, which has no node to configure."
  [{:keys [adapter handler generator concurrency name]}]
  (merge tests/noop-test
         {:pure-generators true
          :name            (or name "jepsen-lite")
          :concurrency     (or concurrency 5)
          :client          (bridge/client
                            ;; `invoke` takes no handler argument, so the
                            ;; adapter carries it. The config is the
                            ;; user-facing place to put it; bind it in here.
                            (cond-> adapter handler (assoc :handler handler)))
          :generator       (or generator gen/default)}))

(defn- realize-history
  "The history `with-history!` hands back reads its chunks from the store file
   on demand, so it dies once the store handle closes. Pull it into memory while
   the handle is still open, as an equivalent in-memory history."
  [history]
  (h/history (into [] history)
             {:already-ops? true, :have-indices? true, :dense-indices? true}))

(defn run
  "Runs `config`:

     {:adapter     <a ClientAdapter>
      :handler     (fn [conn op] ...)  ; the user's op -> target-call mapping
      :generator   <a jepsen generator> ; optional; `lite.gen/default` otherwise
      :concurrency <n>                  ; optional
      :name        \"...\"              ; optional
      :target      {:type :in-process}} ; carried for later milestones; unused

   Interprets the generator against the user's adapter and returns the
   resulting `jepsen.history`, which is also printed.

   `prepare-test` supplies the `:start-time`/`:concurrency`/`:barrier` the
   interpreter needs and makes the generator forgettable; the store handle is
   opened because the interpreter asserts a `:history-writer`. Nothing reads
   that store back — result persistence and analysis come with the checker."
  [config]
  (let [test (jepsen/prepare-test (test-map config))
        history (store/with-handle [test test]
                  ;; save-0! writes the initial test; the history writer refuses
                  ;; to open without the block id it leaves in the test's meta.
                  (let [test (store/save-0! test)]
                    (util/with-relative-time
                      (-> (store/with-history! [test test]
                            (interpreter/run! test))
                          :history
                          realize-history))))]
    (run! pprint/pprint history)
    history))

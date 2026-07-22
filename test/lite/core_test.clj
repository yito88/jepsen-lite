(ns lite.core-test
  "M2 acceptance: the register workload runs end to end, and the checker
   discriminates -- correct target valid, broken target invalid."
  (:require [clojure.test :refer [deftest is testing]]
            [lite.core :as core]
            [lite.demo :as demo]))

(def correct (delay (core/run (demo/config demo/handler))))
(def broken (delay (core/run (demo/config demo/broken-handler))))

(deftest correct-register-is-linearizable
  (let [{:keys [valid? history]} @correct]
    (is (true? valid?))

    (testing "the history is well-formed"
      ;; A process is sequential, so its ops alternate invoke, completion, ...
      ;; Jepsen would have thrown :jepsen.client/invalid-completion during the
      ;; run had the bridge dropped :process or :f.
      (doseq [[process ops] (group-by :process history)
              [invoke complete] (partition-all 2 ops)]
        (is (= :invoke (:type invoke)) (str "process " process))
        (is (some? complete) "no dangling invocation")
        (is (contains? #{:ok :fail :info} (:type complete)))
        (is (= (:process invoke) (:process complete)))
        (is (= (:f invoke) (:f complete)))))

    (testing "cas mismatches happen, and are not violations in themselves"
      (is (some (fn [op] (and (= :fail (:type op)) (= :cas (:f op))))
                history)))

    (testing "reads see writes: the workers share one store"
      ;; Every worker opens its own client, so a read of a written value proves
      ;; they are all talking to the same instance, not to private copies.
      (is (some (fn [op] (and (= :ok (:type op))
                              (= :read (:f op))
                              (some? (val (:value op)))))
                history)))))

(deftest broken-register-is-caught
  (let [{:keys [valid? results]} @broken
        per-key (:results results)]
    (is (false? valid?))

    (testing "it fails as a linearizability violation, not an error"
      (is (seq (:failures results)))
      (let [failed (->> (vals per-key)
                        (remove :valid?)
                        (map :linearizable))]
        (is (seq failed))
        (is (every? false? (map :valid? failed)))
        ;; Knossos explains itself: it reached an op no linearization allows.
        (is (every? (comp seq :final-paths) failed))))))

(ns lite.core-test
  "M3 acceptance: every workload runs end to end, and every checker
   discriminates -- correct target valid, broken target invalid."
  (:require [clojure.test :refer [deftest is testing]]
            [lite.core :as core]
            [lite.demo :as demo]
            [lite.workload :as workload]))

(defn run [workload variant]
  (core/run (demo/config workload {:variant variant})))

(def correct-register (delay (run :register :correct)))

(deftest every-workload-is-checked-in-both-directions
  (doseq [workload (keys workload/workloads)]
    (testing (str (name workload) ", correct target")
      (is (true? (:valid? (if (= :register workload)
                            @correct-register
                            (run workload :correct))))))

    (testing (str (name workload) ", broken target")
      (is (false? (:valid? (run workload :broken)))))))

(deftest broken-targets-fail-for-the-right-reason
  (testing "register: a CAS no linearization allows"
    (let [{:keys [results]} (run :register :broken)
          failed (->> (:results results) vals (remove :valid?) (map :linearizable))]
      (is (seq (:failures results)))
      (is (seq failed))
      ;; Knossos explains itself: it reached an op no linearization allows.
      (is (every? (comp seq :final-paths) failed))))

  (testing "set: acknowledged adds missing from the final read"
    (let [{:keys [results]} (run :set :broken)]
      (is (pos? (:lost-count results)))
      (is (zero? (:unexpected-count results)))))

  (testing "bank: money that stops adding up"
    (let [{:keys [results]} (run :bank :broken)]
      (is (contains? (:errors results) :wrong-total))
      (is (pos? (:error-count results)))))

  (testing "counter: reads below the acknowledged sum"
    (let [{:keys [results]} (run :counter :broken)
          [lower value _upper] (first (:errors results))]
      (is (seq (:errors results)))
      (is (< value lower)))))

(deftest correct-register-history-is-well-formed
  (let [{:keys [history]} @correct-register]
    (testing "every invocation has a matching completion"
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

(deftest unknown-workload-is-rejected
  (is (thrown? clojure.lang.ExceptionInfo (workload/build :nope {}))))

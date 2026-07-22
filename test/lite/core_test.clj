(ns lite.core-test
  "M1 acceptance: the demo, driven by Jepsen's generator interpreter, produces a
   well-formed history."
  (:require [clojure.test :refer [deftest is testing]]
            [lite.core :as core]
            [lite.demo :as demo]))

(def history (delay (core/run demo/config)))

(deftest history-is-well-formed
  (let [h @history]
    (is (seq h))

    (testing "every invocation is followed by a matching completion"
      ;; A process is sequential, so its ops alternate invoke, completion,
      ;; invoke, ... This is what Jepsen validates as well; a mismatch would
      ;; have thrown :jepsen.client/invalid-completion during the run.
      (doseq [[process ops] (group-by :process h)
              [invoke complete] (partition-all 2 ops)]
        (is (= :invoke (:type invoke)) (str "process " process))
        (is (some? complete) "no dangling invocation")
        (is (contains? #{:ok :fail :info} (:type complete)))
        (is (= (:process invoke) (:process complete)))
        (is (= (:f invoke) (:f complete)))))

    (testing "outcomes cover a normal return, a fail! and an info!"
      (let [types (set (map :type h))]
        (is (contains? types :ok))
        (is (contains? types :info))    ; the simulated timeout
        (is (contains? types :fail))))  ; a cas mismatch

    (testing "reads see writes: the workers share one register"
      ;; Every worker opens its own client, so a non-nil read proves they are
      ;; all talking to the same instance rather than to private copies.
      (is (some (fn [op] (and (= :ok (:type op))
                              (= :read (:f op))
                              (some? (:value op))))
                h)))))

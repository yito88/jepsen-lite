(ns lite.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [lite.client :as client]
            [lite.targets :as targets]))

(deftest complete-classifies-outcomes
  (let [op {:type :invoke, :f :read, :value nil, :process 0}]
    (testing "normal return"
      (is (= (assoc op :type :ok :value 42)
             (client/complete (fn [_ _] 42) nil op))))

    (testing "fail!"
      (is (= (assoc op :type :fail :error "nope")
             (client/complete (fn [_ _] (client/fail! "nope")) nil op))))

    (testing "info!"
      (is (= (assoc op :type :info :error :timeout)
             (client/complete (fn [_ _] (client/info! :timeout)) nil op))))

    (testing "an unexpected exception is indeterminate, not ok or fail"
      (let [completed (client/complete (fn [_ _] (throw (RuntimeException. "boom")))
                                       nil op)]
        (is (= :info (:type completed)))
        (is (= {:type :unexpected-exception
                :class "java.lang.RuntimeException"
                :message "boom"}
               (:error completed)))))

    (testing "invoked fields are preserved"
      ;; Jepsen rejects a completion whose :process or :f differs from the
      ;; invocation's.
      (is (= {:f :read, :process 0}
             (select-keys (client/complete (fn [_ _] nil) nil op) [:f :process]))))))

(deftest open-and-close-are-re-runnable
  (let [a (targets/adapter (constantly {}))]
    (dotimes [_ 3]
      (let [conn (client/open a)]
        (is (some? conn))
        (client/close a conn)
        ;; closing twice, and closing a nil conn, must not throw
        (client/close a conn)
        (client/close a nil)))))

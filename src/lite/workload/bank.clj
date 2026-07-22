(ns lite.workload.bank
  "The `:bank` workload: move money between accounts and check that none is
   created or destroyed.

   Generator, defaults and checker come from `jepsen.tests.bank`. This is the
   only workload that requires **multi-key atomic transactions**: a transfer
   debits one account and credits another, and a read that catches a target
   halfway through sees a total that doesn't add up. A target without atomic
   multi-key updates cannot meaningfully run `:bank`.

   ## Handler contract

     :transfer  value is {:from a, :to b, :amount n} -> atomically debit
                `from` and credit `to`. If the transfer is rejected -- say the
                balance is too low -- call `lite.client/fail!`.
     :read      value is nil -> return a map of every account to its balance.

   The workload chooses the accounts and the starting total; reads must cover
   all of them, and (unless `:negative-balances?`) balances may not go negative."
  (:require [jepsen.generator :as gen]
            [jepsen.tests.bank :as bank]))

(defn workload
  "Options:

     :op-limit           Total ops (default 200).
     :negative-balances? If true, balances may go below zero (default false)."
  [{:keys [op-limit negative-balances?]
    :or   {op-limit 200, negative-balances? false}}]
  (let [defaults (bank/test {:negative-balances? negative-balances?})]
    {:generator   (gen/clients (gen/limit op-limit (:generator defaults)))
     ;; bank/test also composes in a gnuplot-backed plotter; the invariant is
     ;; the part that decides the verdict, and it needs no external tools.
     :checker     (bank/checker {:negative-balances? negative-balances?})
     ;; The generator and checker read these from the test map.
     :test-opts   (select-keys defaults [:accounts :total-amount :max-transfer])
     :concurrency 4}))

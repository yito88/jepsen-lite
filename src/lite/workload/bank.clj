(ns lite.workload.bank
  "The `:bank` workload: move money between accounts and check that none is
   created or destroyed.

   Generator, defaults and checker come from `jepsen.tests.bank`. This is the
   only workload that requires **multi-key atomic transactions**: a transfer
   debits one account and credits another, and a read that catches a target
   halfway through sees a total that doesn't add up. A target without atomic
   multi-key updates cannot meaningfully run `:bank`.

   ## Handler contract

     :init      value is {account balance} -> set those balances. Sent once,
                before any transfers, to open the accounts.
     :transfer  value is {:from a, :to b, :amount n} -> atomically debit
                `from` and credit `to`. If the transfer is rejected -- say the
                balance is too low -- call `lite.client/fail!`.
     :read      value is nil -> return a map of every account to its balance.

   The workload chooses the accounts and the starting total, and seeds them
   itself through the same handler as every other op -- the target starts empty
   and knows nothing about banking. Reads must cover all the accounts, and
   (unless `:negative-balances?`) balances may not go negative."
  (:require [jepsen.generator :as gen]
            [jepsen.tests.bank :as bank]))

(defn workload
  "Options:

     :op-limit           Total ops (default 200), or false for as many as the
                         run has time for.
     :negative-balances? If true, balances may go below zero (default false)."
  [{:keys [op-limit negative-balances?]
    :or   {op-limit 200, negative-balances? false}}]
  (let [defaults (bank/test {:negative-balances? negative-balances?})
        accounts (:accounts defaults)
        opening  (zipmap accounts
                         ;; The first account starts with the lot, the rest
                         ;; empty. Every account is named, so a read covers them
                         ;; all from the first op onwards.
                         (cons (:total-amount defaults) (repeat 0)))]
    {:generator   (gen/phases
                   ;; Opening the accounts is the workload's business, not the
                   ;; target's: it goes through the same handler as everything
                   ;; else, and finishes before the first transfer.
                   (gen/clients {:f :init, :value opening})
                   (gen/clients (cond->> (:generator defaults)
                                  op-limit (gen/limit op-limit))))
     ;; bank/test also composes in a gnuplot-backed plotter; the invariant is
     ;; the part that decides the verdict, and it needs no external tools.
     :checker     (bank/checker {:negative-balances? negative-balances?})
     ;; The generator and checker read these from the test map.
     :test-opts   (select-keys defaults [:accounts :total-amount :max-transfer])
     :concurrency 4}))

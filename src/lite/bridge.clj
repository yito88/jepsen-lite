(ns lite.bridge
  "Lite-internal glue between the user's `ClientAdapter` and the
   `jepsen.client/Client` the generator interpreter drives. Users never see
   `jepsen.client/Client`; they only write a ClientAdapter and a handler.

   Like the adapter itself, this is generic: it wraps *any* ClientAdapter over
   *any* target-type's connection, and contains no workload or fault logic."
  (:require [jepsen.client :as jepsen.client]
            [lite.client :as client]
            [lite.target :as target]))

(defrecord AdapterClient [adapter connection]
  jepsen.client/Client
  (open! [this _test _node]
    ;; Jepsen opens a client per worker process, and again whenever a process
    ;; crashes and is replaced -- hence the adapter's re-runnable `open`.
    (target/acquire! connection)
    this)

  (setup! [this _test]
    ;; Reserved for workloads that need one-time initialization.
    this)

  (invoke! [_this _test op]
    ;; Read the conn per op, never once at open!: a fault may have replaced the
    ;; target since the last op, and this client has to follow it.
    ;;
    ;; The adapter's `invoke` runs the user's handler through the
    ;; exception -> :type wrapper, which merges onto the invocation op and so
    ;; preserves :process and :f. Jepsen rejects completions that don't.
    (client/invoke adapter (target/current connection) op))

  (teardown! [_this _test]
    nil)

  (close! [_this _test]
    (target/release! connection)))

(defn client
  "A `jepsen.client/Client` backed by `adapter`, talking to whatever
   `connection` currently points at."
  [adapter connection]
  (->AdapterClient adapter connection))

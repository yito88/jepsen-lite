(ns lite.bridge
  "Lite-internal glue between the user's `ClientAdapter` and the
   `jepsen.client/Client` the generator interpreter drives. Users never see
   `jepsen.client/Client`; they only write a ClientAdapter and a handler.

   Like the adapter itself, this is generic: it wraps *any* ClientAdapter, and
   knows nothing about target-type or about any particular workload."
  (:require [jepsen.client :as jepsen.client]
            [lite.client :as client]))

(defrecord AdapterClient [adapter conn]
  jepsen.client/Client
  (open! [this _test _node]
    ;; Jepsen opens a client per worker process, and again whenever a process
    ;; crashes and is replaced -- hence the adapter's re-runnable `open`.
    (assoc this :conn (client/open adapter)))

  (setup! [this _test]
    ;; Reserved for workloads that need one-time initialization.
    this)

  (invoke! [_this _test op]
    ;; The adapter's `invoke` runs the user's handler through the
    ;; exception -> :type wrapper, which merges onto the invocation op and so
    ;; preserves :process and :f. Jepsen rejects completions that don't.
    (client/invoke adapter conn op))

  (teardown! [_this _test]
    nil)

  (close! [_this _test]
    (client/close adapter conn)))

(defn client
  "A `jepsen.client/Client` backed by `adapter`. Not yet connected; Jepsen calls
   `open!` to get connected copies."
  [adapter]
  (map->AdapterClient {:adapter adapter}))

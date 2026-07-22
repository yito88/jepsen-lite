# Jepsen Lite

A lightweight, scoped-down fault-injection / verification tool built on Jepsen's internals (generator / checker / history / store), with SSH, multi-node clusters, and the full Jepsen lifecycle hidden behind a minimal surface.

This is an independent, unofficial project. For rigorous, production-grade distributed systems testing, use Jepsen directly.

Two orthogonal axes:

1. **ClientAdapter** (`lite.client`) — bound to the target *protocol*. The user
   implements it: connection lifecycle plus the handler that maps ops to target
   calls. It knows nothing about how the target is deployed.
2. **target-type** — `:in-process` / `:local-process` / `:http` / `:compose`;
   the deploy / lifecycle method, which decides what faults can be injected.

## Status: M1

Ops from a `jepsen.generator` flow through the user's ClientAdapter — bridged to
`jepsen.client/Client` internally — and are recorded into a `jepsen.history` of
paired `:invoke` → `:ok`/`:fail`/`:info` operations, which is printed and
returned. No checker yet, and no real workloads.

    clojure -M:run     # runs the demo in src/lite/demo.clj
    clojure -M:test

Handlers signal outcomes by throwing: return normally for `:ok`, call
`(lite.client/fail! msg)` for a certain failure, `(lite.client/info! reason)` for
an indeterminate one. Any other exception is treated as `:info`.

Runs write a store file under `store/` (gitignored). Jepsen's history writer
requires it; nothing reads it back until the checker arrives.

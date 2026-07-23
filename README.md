# Jepsen Lite

A lightweight, scoped-down fault-injection / verification tool built on Jepsen's internals (generator / checker / history / store), with SSH, multi-node clusters, and the full Jepsen lifecycle hidden behind a minimal surface.

This is an independent, unofficial project. For rigorous, production-grade distributed systems testing, use Jepsen directly.

Two orthogonal axes:

1. **ClientAdapter** (`lite.client`) — bound to the target *protocol*. The user
   implements it: connection lifecycle plus the handler that maps ops to target
   calls. It knows nothing about how the target is deployed.
2. **target-type** — `:in-process` / `:local-process` / `:http` / `:compose`;
   the deploy / lifecycle method, which decides what faults can be injected.

## Status: M3

The pipeline runs end to end: a workload's generator → the user's ClientAdapter
(bridged to `jepsen.client/Client` internally) → a `jepsen.history` → the
workload's checker → a verdict. All four v1 workloads are in:

| `:workload` | Checks | Needs from the target |
|---|---|---|
| `:register` | linearizability (Knossos) | compare-and-set |
| `:set` | lost writes / phantom elements | nothing special |
| `:bank` | total balance is conserved | multi-key atomic transactions |
| `:counter` | reads stay within the increment range (lenient) | nothing special |

Each ships a correct demo target and a deliberately broken one:

    clojure -M:run                     # correct register -> :valid? true
    clojure -M:run bank broken         # <workload> [broken] [crash] [volatile]
    clojure -M:run set crash           # crashes, data survives -> :valid? true
    clojure -M:run set crash volatile  # crashes lose data     -> :valid? false
    clojure -M:run bank time=10 concurrency=8
    clojure -M:test

## How long, and how many workers

    (lite.core/run {..., :time-limit 10, :concurrency 8})

`:time-limit` is in seconds, and replaces the workload's default op count, so a
run lasts as long as you asked rather than stopping after a few hundred ops.
Anything the workload has to do at the end — `:set`'s final read — still runs
after the clock stops. Without a time limit, the op count ends the run.

`:concurrency` is how many workers issue ops; leave it out and the workload
picks. `:register` works each key with a group of threads and needs a multiple
of the group size, and says so if given something else.

## Faults

Faults are asked for by intent — `:nemesis [:crash]` — and which ones are
possible depends on how the target is deployed, not on the workload:

| target-type | `:crash` | `:pause` | `:partition` |
|---|---|---|---|
| `:http` | ✗ | ✗ | ✗ |
| `:in-process` | ✓ | ✗ | ✗ |
| `:local-process` | ✓ | ✓ | ✗ |
| `:compose` | ✓ | ✓ | ✓ |

Asking for one of the ✗ combinations stops the run before it starts, with what
went wrong, why, and what to do instead. Only `:in-process` is runnable so far;
its crash destroys the target instance and creates a new one, which is what
`ClientAdapter`'s re-runnable `open`/`close` are for.

The library is `src/`. The demo targets live in `examples/`, on the classpath
only for the `:run` alias, so depending on jepsen-lite doesn't drag them in —
and they use nothing a consumer couldn't. The test suite has its own fixtures in
`test/` and never reads `examples/`.

A user writes a **ClientAdapter**, a **handler**, and picks a `:workload`;
`lite.core/run` returns `{:valid? ..., :results ..., :history ...}`. Each
workload documents its handler contract in its own namespace — see
`lite.workload.register`.

Handlers signal outcomes by throwing: return normally for `:ok`, call
`(lite.client/fail! msg)` for a certain failure, `(lite.client/info! reason)` for
an indeterminate one. Any other exception is treated as `:info`. A CAS mismatch
is an ordinary `:fail`, and a history full of them is still linearizable.

Runs write their history and results under `store/` (gitignored), in Jepsen's
normal store layout.

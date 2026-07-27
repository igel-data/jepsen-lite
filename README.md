# Jepsen Lite

A lightweight, scoped-down fault-injection / verification tool built on Jepsen's internals (generator / checker / history / store), with SSH, multi-node clusters, and the full Jepsen lifecycle hidden behind a minimal surface.

This is an independent, unofficial project. For rigorous, production-grade distributed systems testing, use Jepsen directly.

Two orthogonal axes:

1. **ClientAdapter** (`lite.client`) — bound to the target *protocol*. The user
   implements it: connection lifecycle plus the handler that maps ops to target
   calls. It knows nothing about how the target is deployed.
2. **target-type** — `:in-process` / `:local-process` / `:http` / `:compose`;
   the deploy / lifecycle method, which decides what faults can be injected.

## Status: M5

The pipeline runs end to end: a workload's generator → the user's ClientAdapter
(bridged to `jepsen.client/Client` internally) → a `jepsen.history` → the
workload's checker → a verdict. All four v1 workloads are in, and they run
against both runnable target-types — in-process and over HTTP:

| `:workload` | Checks | Needs from the target |
|---|---|---|
| `:register` | linearizability (Knossos) | compare-and-set |
| `:set` | lost writes / phantom elements | nothing special |
| `:bank` | total balance is conserved | multi-key atomic transactions |
| `:counter` | reads stay within the increment range (lenient) | nothing special |

Each ships a correct demo target and a deliberately broken one:

    clojure -M:run                     # correct register -> :valid? true
    clojure -M:run bank broken         # <workload> [broken] [crash] [buggy]
    clojure -M:run set crash           # survives crashes    -> :valid? true
    clojure -M:run set crash buggy     # loses acked writes  -> :valid? false
    clojure -M:run bank time=10 concurrency=8
    clojure -M:test

The same four workloads run against a store outside Lite, over HTTP. Two
terminals, because that is the shape of the thing — the target is a program
Lite doesn't run:

    clojure -M:serve                   # terminal 1: an HTTP KVS, on :8080
    clojure -M:run-http bank           # terminal 2: the same workloads, over HTTP
    clojure -M:serve broken            # a store with defects ...
    clojure -M:run-http bank broken    # ... which the same checkers catch
    clojure -M:run-http set crash      # refused: see Faults, below

## How long, and how many workers

    (lite.core/run {..., :time-limit 10, :concurrency 8})

`:time-limit` is in seconds, and replaces the workload's default op count, so a
run lasts as long as you asked rather than stopping after a few hundred ops.
Anything the workload has to do at the end — `:set`'s final read — still runs
after the clock stops. Without a time limit, the op count ends the run.

`:concurrency` is how many workers issue ops; leave it out and the workload
picks. `:register` works each key with a group of threads and needs a multiple
of the group size, and says so if given something else.

## Where the target runs

The target-type is the second axis: how the target is deployed, and so what its
connection lifecycle looks like and which faults it can be given.

    :target {:type :in-process}                          ; runs inside Lite's JVM
    :target {:type :http, :url "http://127.0.0.1:8080"}  ; already running, elsewhere

`:in-process` owns the target's whole lifecycle: one instance, shared by every
worker, which the crash nemesis can destroy and re-create. `:http` owns nothing.
The target is a program somebody else started; Lite opens a connection per
worker and does no more. Starting, stopping and preparing an `:http` target is
yours to do — Lite will only say plainly, before the run rather than op by op,
when nothing is listening where you said it would be.

Everything else is shared: the same `:workload` values, the same handler
contracts, the same checkers, the same verdict. Adding `:http` (M5) took a
target-type lifecycle, one line registering it, and a demo server; the
ClientAdapter protocol, the workloads, the bridge, the exception→`:type`
wrapper and the checker/store path were not touched. HTTP errors need no new
code either — a rejected op and a refused connection are `fail!`, a timeout is
`info!`, through the wrapper that was already there. That orthogonality is the
bet the design made in M0, and testing it is what M5 was for.

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
went wrong, why, and what to do instead. `:in-process` and `:http` are runnable
so far; `:http`'s whole row is ✗, because Lite doesn't run that target and so
has nothing to crash, pause or cut off.

`:in-process`'s crash destroys the target instance and creates a new one —
`close` then `open` — which is what `ClientAdapter`'s re-runnable lifecycle is
for. **`open` attaches to durable state; it must not create or reset it.** A
store that persists therefore survives a crash with its committed data intact, and the
checker passes. When acknowledged writes go missing afterwards, that's a
durability bug in the target, and the checker says so. Lite doesn't decide what
should survive — it crashes the target, records what happened, and lets the
checker rule.

Whatever initial state a workload needs, the workload writes itself, through the
same handler as every other op — `:bank` opens its accounts with an `:init` op
in a first generator phase. Adapters stay workload-agnostic, and initialization
doesn't silently re-run on every crash.

The library is `src/`. The demo targets live in `examples/`, on the classpath
only for the `:run` / `:serve` / `:run-http` aliases, so depending on
jepsen-lite doesn't drag them in — and they use nothing a consumer couldn't.
The test suite has its own fixtures in `test/` and never reads `examples/`.

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

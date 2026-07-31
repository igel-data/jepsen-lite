[![Jepsen Lite](https://img.shields.io/clojars/v/com.igel-data/jepsen-lite.svg)](https://clojars.org/com.igel-data/jepsen-lite)

# Jepsen Lite

A lightweight, scoped-down fault-injection / verification tool built on Jepsen's internals (generator / checker / history / store), with SSH, multi-node clusters, and the full Jepsen lifecycle hidden behind a minimal surface.

This is an independent, unofficial project. For rigorous, production-grade distributed systems testing, use Jepsen directly.

Two orthogonal axes:

1. **ClientAdapter** (`lite.client`) — bound to the target *protocol*. The user
   implements it: connection lifecycle plus the handler that maps ops to target
   calls. It knows nothing about how the target is deployed.
2. **target-type** — `:in-process` / `:local-process` / `:http` / `:compose`;
   the deploy / lifecycle method, which decides what faults can be injected.

## Status: M7.1 — reusable suite runner

The pipeline runs end to end: a workload's generator → the user's ClientAdapter
(bridged to `jepsen.client/Client` internally) → a `jepsen.history` → the
workload's checker → a verdict. All four v1 workloads are in, and all four
target-types run them — in-process, over HTTP, as an OS process Lite kills, and
as a container Lite can cut off the network:

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

And against a store Lite runs itself, as a process it can `kill -9` — one
terminal, because there is nobody else to start it:

    clojure -M:run-local counter              # no faults
    clojure -M:run-local counter crash        # kill -9, restart, recover
    clojure -M:run-local counter crash unsafe # a store that buffers -> caught
    clojure -M:run-local set pause            # SIGSTOP / SIGCONT

    clojure -M:run-compose set partition      # in a container (needs Docker)

Same store, same handlers, same checkers throughout. What changes between those
four blocks is the `:target`.

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

    :target {:type :in-process}
    :target {:type :http,      :url "http://127.0.0.1:8080"}
    :target {:type :local-process, :command ["my-server" "--port" "8080"]
                                   :url "http://127.0.0.1:8080"}
    :target {:type :compose,   :file "docker-compose.yml"
                               :container "my-store", :url "http://127.0.0.1:8080"}

The pattern behind the whole axis is one sentence: **a fault is possible where
Lite owns the thing the fault happens to.**

| | Lite owns | so it can |
|---|---|---|
| `:in-process` | an object in its own JVM | destroy and rebuild it |
| `:http` | nothing — the target was already running | connect, and nothing else |
| `:local-process` | an OS process it started | `kill -9` it, `SIGSTOP` it |
| `:compose` | a container, with a network interface of its own | all of that, and cut it off |

Everything else is shared: the same `:workload` values, the same handler
contracts, the same checkers, the same verdict. Each new target-type has cost a
file under `src/lite/target/`, a line registering it, and an example — the
ClientAdapter protocol, the workloads, the bridge, the exception→`:type`
wrapper and the checker/store path have not been touched since M4.5. Wire
errors need no new code either: a rejected op and a refused connection are
`fail!`, a timeout or a connection dropped mid-request is `info!`, through the
wrapper that was already there. That orthogonality was the bet the design made
in M0, and M5 and M6 are what tested it.

## What a `crash` actually tests — and what `power-off` does

It depends on what Lite is holding, and it is worth being exact about, because
a crash test that proves less than you think is worse than none:

- **`:in-process`** — `close` then `open`. A clean shutdown and recovery. It
  exercises a target's recovery path, and not the process boundary at all.
- **`:local-process` / `:compose`** — a real `SIGKILL`. No flush, no close, no
  shutdown hook, then a real restart that has to find its data on disk. This
  catches a store that acknowledged writes it was still holding in **its own
  memory**, which is a real and common bug.

None of them tests `fsync`. SIGKILL kills the process, not the kernel, so
writes the store handed to the OS get written back anyway — a store that
fsyncs what it acknowledges and one that merely `write()`s it come through a
crash test identically.

**`:power-off`** is the fault that asks. Lite mounts the target's data
directory on [lazyfs](https://github.com/dsrhaslab/lazyfs), which holds writes
in a cache of its own until an explicit fsync; each power-off clears that cache
— waiting for lazyfs to confirm — and *then* SIGKILLs, so the restarted target
recovers from a disk that lost precisely what it never synced.

The demo shows the difference in three lines. The same store, one fault apart:

    clojure -M:run-local counter power-off          # fsyncs      -> :valid? true
    clojure -M:run-local counter power-off nofsync  # doesn't     -> :valid? false
    clojure -M:run-local counter crash    nofsync   # same store  -> :valid? true

`:power-off` is `:local-process` only and **Linux only** — lazyfs is FUSE. On
any other host, asking for it stops the run with what's missing and how to fix
it. It is never quietly downgraded to a plain crash, because that would report
durability nobody tested.

    JEPSEN_LITE_LAZYFS=/path/to/lazyfs/lazyfs clojure -M:run-local counter power-off

### It only means something if the target actually fsyncs

A store configured not to sync will "fail" a power-off trivially, and that
result says nothing at all. Before reading a failure as a finding, check:

- **SQLite** — needs `PRAGMA synchronous=FULL` (with a journal mode to match).
  At `OFF` or `NORMAL` it may skip or reduce fsync, and losing data is then the
  documented behaviour, not a bug.
- **LMDB** — must not be opened with `MDB_NOSYNC` or `MDB_NOMETASYNC`; mind
  what `MDB_WRITEMAP` implies too.

The rule in general: **power-off tests durability only if the target is
configured to fsync.** The interesting result is a store that *claims*
durability and still loses an acknowledged write.

Neither of those two bullets is advice taken on trust; both are measured, in
`cases/`. SQLite at `synchronous=OFF` loses over 1200 of some 1600 acknowledged
writes to a power-off and **not one** to a `kill`. LMDB with `MDB_NOSYNC` is
worse than losing them: in four runs of five the environment could not be
reopened at all — `MDB_INVALID` — and again a `kill` costs it nothing. Set to
sync, both come through every fault whole.

That contrast is also the argument that this fault does what it says. A pair of
real stores that fail a power-off exactly when they are configured not to sync,
and pass it exactly when they are, is better evidence than any test in `test/`
can be.

**`open` attaches to durable state; it must not create or reset it.** That
holds at every level — an object, a data directory, a container volume. A
target that came back empty after a crash would be passing the test by
forgetting the question.

## Faults

Faults are asked for by intent — `:nemesis [:crash]` — and which ones are
possible depends on how the target is deployed, not on the workload:

| target-type | `:crash` | `:pause` | `:partition` | `:power-off` |
|---|---|---|---|---|
| `:http` | ✗ | ✗ | ✗ | ✗ |
| `:in-process` | ✓ | ✗ | ✗ | ✗ |
| `:local-process` | ✓ | ✓ | ✗ | ✓ |
| `:compose` | ✓ | ✓ | ✓ | ✗ |

Asking for one of the ✗ combinations stops the run before it starts, with what
went wrong, why, and what to do instead. `:http`'s row is all ✗ because Lite
doesn't run that target and so has nothing to crash, pause or cut off;
`:local-process` can't be partitioned because it reaches Lite over loopback —
there is no network in between to cut; and `:compose` can't be powered off
because a FUSE mount inside a container needs capabilities Lite doesn't ask
for yet. That last one is deferred rather than impossible.

How each is carried out:

| intent | `:in-process` | `:local-process` | `:compose` |
|---|---|---|---|
| `:crash` | `close` then `open` | `SIGKILL`, then restart | `pumba kill`, then `up -d` |
| `:pause` | — | `SIGSTOP` / `SIGCONT` | `pumba pause --duration` |
| `:partition` | — | — | `pumba netem loss 100%` |
| `:power-off` | — | lazyfs `clear-cache`, then `SIGKILL` | — |

Pumba runs as a container with the docker socket mounted, so there is nothing
to install beyond Docker. `netem` needs `tc`, which a target's own image is
unlikely to carry, so Lite passes `--tc-image` — without it the fault silently
does nothing, which is worse than failing, because the run would look like a
partition test that passed.

Lite doesn't decide what should survive a fault — it perturbs the target,
records what happened, and lets the checker rule. When acknowledged writes go
missing afterwards, that's a durability bug in the target, and the checker says
so.

Ops that land while a target is down are recorded by the outcome contract that
was already there, and the distinction matters to the verdict: a refused
connection is a **`:fail`** (the request certainly never arrived), while a
timeout or a connection dropped mid-request is an **`:info`** — nobody knows,
and a checker that was told otherwise would be being lied to.

Whatever initial state a workload needs, the workload writes itself, through the
same handler as every other op — `:bank` opens its accounts with an `:init` op
in a first generator phase. Adapters stay workload-agnostic, and initialization
doesn't silently re-run on every crash.

## One runner for a target's test suite

`lite.core/run` remains the direct API for one config. A target with several
workloads or deployment shapes can instead declare a suite and use Lite's
common CLI:

```clojure
(def suite
  {:name              "my-store"
   :workloads         [:bank :register :set :counter]
   :default-workloads :all
   :default-profile   :in-process
   :profiles
   {:in-process {:build config}
    :process    {:build kill-config}}
   :options
   {:sync   {:values ["on" "off"]
             :parse #(not= "off" %)
             :key :sync?
             :doc "store sync mode"}
    :lazyfs {:key :lazyfs-dir
             :doc "path to the lazyfs executable"}}})
```

Each profile's `:build` is an ordinary
`(fn [workload opts] <lite.core/run config>)`. The target still owns its
adapter, handlers, process command and target-specific defaults; Lite owns
argument parsing, workload repetition, summary output and exit status.

Point an alias at the suite var:

```clojure
:main-opts ["-m" "lite.runner" "my-store.runner/suite"]
```

Then every suite has the same command line:

```sh
clojure -M:jepsen --list
clojure -M:jepsen --workload bank
clojure -M:jepsen --profile process --workload set --fault crash
clojure -M:jepsen --profile process --workload set --fault power-off --sync off
```

Workloads, profiles and faults are separate choices: a profile says how the
target is deployed; a fault says what Lite should do to it. `--help` includes
the suite's own typed options. SQLite and LMDB under `cases/` are complete
examples.

## Verifying a real store

The reason the project exists: point a lightweight harness at a persistent
key-value store and `kill -9` it, to find out whether what it acknowledged is
still there afterwards.

[IgelDB](https://github.com/igel-data/igeldb) is verified this way, from its
own repository — see `jepsen/` there. Being embedded, it needs a small driver
process to be killable at all; that driver, and the adapter that talks to it,
live with IgelDB rather than here, which is the right way round: a target's
integration is the target's business, and jepsen-lite has no dependency on
anything it tests.

    clojure -M:jepsen set kill    # in the igeldb repo

A recent run: **1958 acknowledged writes, none lost, across five SIGKILLs** —
plus eight writes that came back `:info` because the connection died
mid-request and turned out to have been committed, which the checker reports as
`recovered` rather than as errors. That is what the indeterminate outcome is
*for*.

Other cases for real stores like SQLite are in `cases/`.

## Layout

The library is `src/`. The demo targets live in `examples/`, on the classpath
only for the demo aliases (`:run`, `:serve`, `:run-http`, `:run-local`,
`:run-compose`), so depending on jepsen-lite doesn't drag them in — and they
use nothing a consumer couldn't. `cases/` is off this classpath altogether: a
project and a `deps.edn` of its own per store. The test suite has its own
fixtures in `test/` and never reads `examples/`.

    clojure -M:test                                   # everything but Docker
    JEPSEN_LITE_DOCKER=1 clojure -M:test -n lite.compose-docker-test

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

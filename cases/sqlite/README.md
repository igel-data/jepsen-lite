# SQLite × Jepsen Lite

Fault-injection / consistency verification of SQLite with
[Jepsen Lite](https://github.com/igel-data/jepsen-lite), in two shapes:

- **in-process** — Jepsen Lite opens and closes SQLite inside its own JVM.
  `src/sqlite/in_process.clj` is the adapter; the crash nemesis is `close` then
  `open`.
- **a separate process, killed with SIGKILL** — `src/sqlite/driver.clj` puts
  SQLite behind a small HTTP API and runs as a program of its own. Jepsen Lite
  starts it, `kill -9`s it mid-run, and starts it again; `src/sqlite/client.clj`
  is the adapter that talks to it. SQLite is an embedded library, not a server,
  so this driver is the only way anything can signal it at all.

The workloads, the checkers and the verdicts are identical in both. What differs
is the adapter (the protocol SQLite is reached by) and the target-type (how it
is deployed) — which is Jepsen Lite's whole design premise, and the reason
`kill` cost a driver and a client rather than a second test suite. The SQL
itself lives in `src/sqlite/db.clj` and is shared, so the two shapes cannot
drift apart.

## Run

```sh
clojure -M:jepsen
clojure -M:jepsen --workload bank
clojure -M:jepsen --workload set --fault crash
clojure -M:jepsen --profile process --workload set --fault crash
clojure -M:jepsen --profile process --workload counter --fault pause
clojure -M:jepsen --workload bank --time-limit 30 --concurrency 8
clojure -M:jepsen --profile process --workload set --journal delete
```

And the fault a `kill -9` cannot be — on Linux, with lazyfs, from the repository root:

```sh
cases/power-off sqlite --profile process --workload set --fault power-off
cases/power-off sqlite --profile process --workload set --fault power-off --sync off
cases/power-off sqlite --profile process --workload set --fault crash --sync off
```

`cases/power-off` runs the case inside a container with lazyfs and `/dev/fuse`,
because FUSE means Linux. On a Linux host the same
`--profile process --fault power-off` command works directly once
`JEPSEN_LITE_LAZYFS` points at a built lazyfs checkout.

The deployment and the fault are separate choices: `--profile process` puts
SQLite in a process Jepsen Lite owns, while `--fault crash`, `--fault pause`,
or `--fault power-off` says what should happen to it. `power-off` is its own
fault rather than an addition to `crash`: it clears lazyfs's cache and then
kills.

Exits non-zero if any workload's verdict is `:valid? false`. Histories and
results are written under `store/` in Jepsen's normal layout; the databases the
runs are made against are under `jepsen-data/`, wiped fresh before each one — a
crash test means nothing if what it recovers turns out to be the previous run's.

| workload | checks | SQLite feature exercised |
|---|---|---|
| `bank` | total balance conserved | multi-key atomic transactions |
| `register` | linearizability (Knossos) | single-statement compare-and-set |
| `set` | no lost / phantom writes | durability of acknowledged writes |
| `counter` | reads within increment range | read-modify-write under contention |

## Three faults, and what each proves

Worth being exact about, because a crash test that proves less than you think is
worse than none.

`crash` (**in-process**) is `close` then `open`: a *clean* shutdown and
recovery. Closing the last connection to a WAL database **checkpoints it and
removes the log**, so what reopens afterwards is a checkpointed database file.
That exercises the checkpoint path and reopening, and it does not touch the
process boundary at all.

`kill` (**separate process**) is a real `SIGKILL`. No close, no checkpoint, no
shutdown hook — the process is simply gone, and recovery has to replay whatever
`-wal` the dead process left behind. `wal_autocheckpoint` is set low (64 pages)
so checkpoints do happen between kills, which puts *both* recovery paths inside
the window rather than only WAL replay. Faults are spaced three seconds apart,
comfortably past the ~2s a JVM restart costs, so there is a real window of
acknowledged writes between them.

**Neither of those tests `fsync`**, and that is not a matter of degree. A
SIGKILL kills the process, not the kernel, so writes SQLite handed to the OS are
written back regardless — `synchronous=FULL` and `synchronous=OFF` come through
a kill *identically*. No number of repetitions changes it; there is no window to
hit.

`power-off` is the fault that asks. Jepsen Lite mounts the driver's data
directory on [lazyfs](https://github.com/dsrhaslab/lazyfs), which holds writes
in a cache of its own until an fsync; each power-off clears that cache — waiting
for lazyfs to confirm — and *then* SIGKILLs, so the restarted driver recovers
from a disk that lost precisely what SQLite never synced.

The three runs above are the whole argument, and they come out like this:

| | `kill` | `power-off` |
|---|---|---|
| `synchronous=FULL` | 0 lost | **0 lost** |
| `synchronous=OFF` | 0 lost | **1238 of 1598 lost** |

The bottom-left cell is the point: a store that never fsyncs passes a kill test
cleanly. Only the right-hand column can tell the two configurations apart.

## The two things this is really about

### 1. `BEGIN IMMEDIATE`, not `BEGIN DEFERRED`

Every read-modify-write in `db.clj` takes the write lock *before* it reads.

A deferred transaction that reads and then writes has to upgrade its lock, and
in WAL mode that upgrade fails with `SQLITE_BUSY_SNAPSHOT` if anyone committed
in between. `busy_timeout` does not apply to it — no amount of waiting can give
a stale snapshot back its future — and retrying only the *write* at that point
silently loses an update. The transaction has to be rolled back and re-run from
the read. `BEGIN IMMEDIATE` makes the whole situation impossible, which is why
it is what a correct SQLite integration uses, and it is what `counter` and
`bank` are built on.

Single-statement operations use no explicit transaction at all: SQLite wraps
every statement in an implicit one, so `register`'s CAS is a single atomic
`UPDATE registers SET v = ? WHERE k = ? AND v = ?`, and `bank`'s read is a
single `SELECT` that can never catch a transfer with one leg applied.

### 2. What each failure is *certain* about

The distinction the checkers depend on:

| what happened | recorded as |
|---|---|
| `BEGIN IMMEDIATE` hit `SQLITE_BUSY` — the lock was never taken | `:fail`, certain |
| the operation was refused — insufficient funds, a CAS mismatch | `:fail`, certain |
| connection refused — the driver was dead, the request never arrived | `:fail`, certain |
| **`COMMIT` failed** | `:info` — **indeterminate** |
| the connection died mid-request, or timed out | `:info` — indeterminate |

A failed `COMMIT` is deliberately *not* turned into a failure. Rolling back
afterwards and calling it certain would be a guess dressed up as a fact: the
rollback can itself fail, and an I/O error at commit can leave the transaction
in a state neither side can see into. `:info` is what the checkers are built to
handle, and it costs the run nothing. A checker that has been lied to can prove
anything.

Both shapes make the same translation, which is why it is worth naming twice:
the driver answers 409 and 500, and the in-process adapter calls
`lite.client/fail!` and `info!` — the same distinction, with and without a wire
in between.

## Honest limits

**`synchronous` is validated under `power-off`, and only there.** Under `crash`
and `kill` it is not: SIGKILL leaves the page cache alone, so `FULL`, `NORMAL`
and `OFF` are indistinguishable, and a green `kill` run is no evidence at all
about fsync. The measured contrast is in the table above.

**A `power-off` failure only means something if the store was asked to fsync.**
`sync=off` losing 1238 writes is the fault injector working, not a SQLite bug —
at `synchronous=OFF` losing them is documented behaviour. The interesting result
would be a store that *claims* durability and still loses an acknowledged write.

**`power-off` models the filesystem, not the device.** lazyfs drops what was
never fsynced; it does not model a drive's own write cache, so on macOS the
`F_FULLFSYNC` gap — `fsync()` there does not flush the drive cache unless
`PRAGMA fullfsync` is on — is still out of scope. Nor does it explore the write
*reordering* a real crash permits; that needs `dm-log-writes`-style replay.

**`:partition` is not available.** A `:local-process` target reaches Jepsen Lite
over loopback, so there is no network in between to cut — and cutting it would
test the driver's HTTP handling rather than SQLite's.

**Correct implementations only.** There are no deliberately-broken twins in this
repo, so a green run is evidence that the integration is right, not proof that
the checkers would fire on one that wasn't.

**What it does test:** that checkpointing, WAL replay and rollback-journal
recovery bring back everything SQLite acknowledged, across both a clean reopen
and a real process death; that concurrent transactions against one database file
conserve the bank invariant and keep registers linearizable; and that the
client's `:fail` / `:info` classification is honest about what it can and cannot
know.

## Recent runs

In-process — `close` and reopen:

```
set crash          201522 acknowledged writes, 0 lost, 8 close/reopen cycles
                   32 more came back :info (they landed in the moment between
                   the close and the open); 6 of those turned out to have been
                   committed — reported as `recovered`, not as errors

bank crash         238420 reads, every one totalling 100, across 8 cycles
  time=20            192461 transfers committed, 43575 refused for insufficient
  concurrency=8      funds, 41 indeterminate. No SQLITE_BUSY failures at all:
                     busy_timeout absorbed the contention, which is its job

register crash     27077 independent registers, every one linearizable, 8 cycles
  time=10
```

Separate process — a real `kill -9`:

```
set kill           161505 acknowledged writes, 0 lost, 5 kills, 1 recovered

bank kill          170001 reads, every one totalling 100, across 5 kills
  time=30            139164 transfers committed, 30996 refused, 37 indeterminate
  concurrency=8

counter pause      3 SIGSTOP/SIGCONT cycles; requests that landed in the window
                   timed out into :info, and every read stayed within range

set kill           39109 acknowledged writes, 0 lost, 5 kills — the other
  journal=delete     durability path, at about a quarter of WAL's throughput
```

Power-off — lazyfs drops what was never fsynced, then SIGKILL:

```
all four           bank + register + set + counter, 15 power-offs, every
  power-off          verdict :valid? true at synchronous=FULL

set power-off      1602 acknowledged writes, 0 lost. SQLite fsyncs what it
                   acknowledges, and it holds

set power-off      1598 acknowledged, 1238 LOST, :valid? false --
  sync=off           the same store, one pragma apart

set kill           1599 acknowledged, 0 lost, :valid? true -- that same broken
  sync=off           configuration, sailing through the fault that can't see it
```

## Layout

| file | what it is |
|---|---|
| `src/sqlite/db.clj` | SQLite itself: the pool, the pragmas, the transactions, the operations. Shared by both shapes |
| `src/sqlite/in_process.clj` | the adapter that calls it directly, in Jepsen Lite's JVM |
| `src/sqlite/driver.clj` | the same operations behind HTTP — the process that gets killed |
| `src/sqlite/client.clj` | the adapter that speaks HTTP to that process |
| `src/sqlite/runner.clj` | the runner: both run configs, CLI, `-main` |

`db.clj` and `driver.clj` depend on nothing but `sqlite-jdbc`, and know nothing
about Jepsen Lite: the driver has to be runnable, and killable, on its own.

```sh
clojure -M:driver --port 8080 --data-dir ./jepsen-data/manual \
        --journal wal --sync full
curl -X POST http://127.0.0.1:8080/bank/read -d '{}'
```

The `:power-off` runs need Linux, `/dev/fuse` and a built lazyfs; `cases/docker/` and
`cases/power-off` provide all three.

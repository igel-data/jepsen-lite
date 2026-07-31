# LMDB × Jepsen Lite

Fault-injection / consistency verification of LMDB with
[Jepsen Lite](https://github.com/igel-data/jepsen-lite), in two shapes:

- **in-process** — Jepsen Lite opens and closes LMDB inside its own JVM.
  `src/lmdb/in_process.clj` is the adapter; the crash nemesis is `close` then
  `open`.
- **a separate process, killed with SIGKILL** — `src/lmdb/driver.clj` puts LMDB
  behind a small HTTP API and runs as a program of its own. Jepsen Lite starts
  it, `kill -9`s it mid-run, and starts it again; `src/lmdb/client.clj` is the
  adapter that talks to it. LMDB is an embedded library, not a server, so this
  driver is the only way anything can signal it at all.

The workloads, the checkers and the verdicts are identical in both. What differs
is the adapter (the protocol LMDB is reached by) and the target-type (how it is
deployed). The LMDB itself lives in `src/lmdb/db.clj` and is shared, so the two
shapes cannot drift apart.

## Requirements

lmdbjava is a JNR-FFI binding rather than a library carrying its own native
code, and it publishes no pre-built native for arm64 macOS — so this suite asks
for **a system liblmdb** everywhere, which is one rule instead of a table of
exceptions:

```sh
brew install lmdb          # macOS
apt install liblmdb0       # Debian / Ubuntu
dnf install lmdb-libs      # Fedora
```

`src/lmdb/native.clj` finds it, and says so plainly if it can't. Set
`LMDB_NATIVE_LIB` if yours lives somewhere unusual.

## Run

```sh
clojure -M:jepsen
clojure -M:jepsen --workload bank
clojure -M:jepsen --workload set --fault crash
clojure -M:jepsen --profile process --workload set --fault crash
clojure -M:jepsen --profile process --workload counter --fault pause
clojure -M:jepsen --workload bank --time-limit 30 --concurrency 8
clojure -M:jepsen --profile process --workload set --sync off
```

And the fault a `kill -9` cannot be — on Linux, with lazyfs, from the repository root:

```sh
cases/power-off lmdb --profile process --workload set --fault power-off
cases/power-off lmdb --profile process --workload set --fault power-off --sync off
cases/power-off lmdb --profile process --workload set --fault crash --sync off
```

`cases/power-off` runs the case inside a container with lazyfs and `/dev/fuse`,
because FUSE means Linux. On a Linux host the same
`--profile process --fault power-off` command works directly once
`JEPSEN_LITE_LAZYFS` points at a built lazyfs checkout.

The deployment and the fault are separate choices: `--profile process` puts
LMDB in a process Jepsen Lite owns, while `--fault crash`, `--fault pause`, or
`--fault power-off` says what should happen to it. `power-off` is its own fault
rather than an addition to `crash`: it clears lazyfs's cache and then kills.

Exits non-zero if any workload's verdict is `:valid? false`. Histories and
results are written under `store/` in Jepsen's normal layout. Each environment
is created in a unique directory under `jepsen-data/`; an existing directory is
never deleted or reused, and a crash cannot recover the previous run's state.

| workload | checks | LMDB feature exercised |
|---|---|---|
| `bank` | total balance conserved | multi-key atomic write transactions |
| `register` | linearizability (Knossos) | CAS built from a write transaction |
| `set` | no lost / phantom writes | durability of acknowledged commits |
| `counter` | reads within increment range | read-modify-write in one transaction |

## The three things this is really about

### 1. There is exactly one writer, and it is exclusive from the start

`txnWrite` takes LMDB's single writer lock and blocks until it has it. There is
no lock to upgrade, so none of the read-then-write hazards that a
deferred-transaction database has can arise: every read-modify-write here — the
CAS, the counter increment, the transfer — is serializable by construction.

The price is that writes do not run concurrently at all, and these workloads are
how you see it. `bank kill time=30 concurrency=8` commits around 7,300 transfers
where the SQLite suite commits 139,000 on the same machine: eight workers, one
writer, and every one of them waiting its turn. Nothing is refused for
contention — there is no busy error to handle, because *waiting is the
mechanism*. That is also why the client's request timeout is 30 seconds rather
than a few: cutting a queued writer off would manufacture an indeterminate op
out of a target that was working perfectly.

Reads never block and are never blocked. A read transaction is a snapshot, so
`bank-read` sees every account as of one instant and can never catch a transfer
with one leg applied — reading the accounts one at a time, each in its own
transaction, is precisely the mistake `db.clj` is written to avoid.

### 2. A crash needs no recovery

LMDB is copy-on-write with two meta pages, and a commit ends by flipping to
whichever one it just wrote. There is no log and no replay: a killed process
leaves a database whose last valid meta page is the last committed transaction,
and opening it afterwards is not so much a recovery as a read.

That is the claim `set kill` puts under load, and it is why the two shapes prove
more nearly the same thing here than they would for a database with a journal.
There is no checkpoint to take and nothing to flush, so a clean close leaves the
same last-committed database that a killed process does. What `kill` adds — and
it is worth having — is that the process really was stopped mid-write rather
than politely waited for.

But **neither of them tests `fsync`**, and that is not a matter of degree. A
SIGKILL kills the process, not the kernel, so writes LMDB handed to the OS are
written back regardless; a synced environment and an `MDB_NOSYNC` one come
through a kill *identically*. No number of repetitions changes it — there is no
window to hit.

`power-off` is the fault that asks. Jepsen Lite mounts the driver's data
directory on [lazyfs](https://github.com/dsrhaslab/lazyfs), which holds writes
in a cache of its own until an fsync; each power-off clears that cache — waiting
for lazyfs to confirm — and *then* SIGKILLs. The restarted environment finds a
meta page that points only at what actually got synced.

The three runs in the Run section come out like this:

| | `kill` | `power-off` |
|---|---|---|
| default (fsync on commit) | 0 lost | **0 lost** |
| `MDB_NOSYNC` | 0 lost | **corrupted, or writes lost** |

The bottom-left cell is the point. It is also the correction to a claim an
earlier version of this README made on the strength of a `kill` run alone.

The bottom-right cell needs saying carefully, because it came out worse than
expected. In four of five runs the environment could not be *reopened at all* —
`MDB_INVALID`, "File is not a valid LMDB file" — so the workload's closing read
never happened and the verdict is `:unknown` rather than `false`. The remaining
run did reopen, and had lost 1506 of its 1518 acknowledged writes.

Both are exactly what LMDB documents. Its own header says `MDB_NOSYNC` means "a
system crash can **corrupt the database** or lose the last transactions", and
promises integrity only "if the filesystem preserves write order". lazyfs's
clear-cache does not preserve write order — it drops everything unsynced at
once, which can leave a meta page referring to data pages that never landed.
The precondition doesn't hold, and neither does the promise.

### 3. What each failure is *certain* about

The distinction the checkers depend on:

| what happened | recorded as |
|---|---|
| the operation was refused — a CAS mismatch, insufficient funds | `:fail`, certain |
| the transaction aborted — map full, reader table full | `:fail`, certain |
| the environment was closed, or not yet reopened, by the crash nemesis | `:fail`, certain |
| connection refused — the driver was dead, the request never arrived | `:fail`, certain |
| **the commit failed** | `:info` — **indeterminate** |
| the connection died mid-request, or timed out | `:info` — indeterminate |

The third row is worth its place. Jepsen Lite hands a worker a nil connection
during the moment between the close and the open, and the easy thing is to let
that become a `NullPointerException` and be filed as indeterminate. But we know
better than that: nothing reached LMDB, so the operation certainly did not
happen, and `db.clj` checks and refuses rather than guessing. In a run of 6,094
acknowledged writes, that is 288 ops reported precisely instead of vaguely.

A failed *commit* is the one place the answer is genuinely unknown, and it is
deliberately not turned into a failure: LMDB's commit writes the data pages and
then flips the meta page, and a failure part-way through is exactly the case
nobody outside can distinguish. `:info` is what the checkers are built to
handle, and it costs the run nothing. A checker that has been lied to can prove
anything.

Both shapes make the same translation: the driver answers 409 and 500, and the
in-process adapter calls `lite.client/fail!` and `info!`.

## Honest limits

**`MDB_NOSYNC` is validated under `power-off`, and only there.** Under `crash`
and `kill` it is not: SIGKILL leaves the page cache alone, so a synced
environment and an unsynced one are indistinguishable. `set kill sync=off`
acknowledges **286,391 writes and loses none**, against 3,687 with sync on — a
78× speed-up and an identical verdict. Reading that as "`MDB_NOSYNC` is safe"
would be a serious mistake, and `set power-off sync=off` is the proof: the same
configuration comes back corrupt, or missing almost everything it acknowledged.
Not a rare event a longer kill run would eventually have found — a different
failure domain.

**A `power-off` failure only means something if the store was asked to fsync.**
`sync=off` losing almost everything is the fault injector working, not an LMDB
bug — LMDB's own header says `MDB_NOSYNC` means "a system crash can corrupt the
database or lose the last transactions". The interesting result would be a store
that *claims* durability and still loses an acknowledged write.

**`power-off` models the filesystem, not the device.** lazyfs drops what was
never fsynced; it does not model a drive's own write cache, so a disk that
acknowledges a flush it hasn't performed is still out of scope.

It does, however, reach further into *ordering* than I first credited it with.
Dropping every unsynced write at once is itself an ordering violation, and it is
enough to produce the corruption LMDB warns about — even without `MDB_WRITEMAP`,
which is the flag the documentation pairs that risk with. What it still does not
do is *explore* orderings: it drops one particular set, deterministically, where
a real crash may permit many. Systematically replaying the permitted ones needs
`dm-log-writes`-style tooling.

**`:partition` is not available.** A `:local-process` target reaches Jepsen Lite
over loopback, so there is no network in between to cut — and cutting it would
test the driver's HTTP handling rather than LMDB's.

**Correct implementations only.** There are no deliberately-broken twins in this
repo, so a green run is evidence that the integration is right, not proof that
the checkers would fire on one that wasn't.

**The map size is fixed at 1 GiB.** LMDB maps its whole size up front, and a run
that outgrew it would abort transactions for a reason that has nothing to do
with what is being asked. `MDB_MAP_FULL` is handled honestly if it ever
arrives — it aborts the transaction, so it is a certain failure — but the size
is set so it doesn't.

**What it does test:** that a killed process leaves a database containing every
commit it acknowledged, with no recovery step; that concurrent transactions
conserve the bank invariant and keep registers linearizable while LMDB
serializes them; and that the client's `:fail` / `:info` classification is
honest about what it can and cannot know.

## Recent runs

In-process — `close` and reopen:

```
set crash          6094 acknowledged writes, 0 lost, 8 close/reopen cycles
                   288 refused outright, every one because the environment was
                   away — reported as :fail, not guessed at

bank crash         9570 reads, every one totalling 100, across 8 cycles
  time=30            7505 transfers committed, 1943 refused for insufficient
  concurrency=8      funds, 300 refused mid-crash. Zero indeterminate

register crash     5084 independent registers, every one linearizable, 8 cycles
```

Separate process — a real `kill -9`:

```
set kill           3687 acknowledged writes, 0 lost, 5 kills
                   19 refused (connection refused — certain) and 19
                   indeterminate (the connection died mid-request)

bank kill          8913 reads, every one totalling 100, across 5 kills
  time=30            7298 transfers committed, 1835 refused, 36 indeterminate
  concurrency=8

counter pause      3 SIGSTOP/SIGCONT cycles; 4 requests timed out into :info,
                   and every read stayed within the increment range

set kill           286391 acknowledged writes, 0 lost, 4 kills — 78× the
  sync=off           throughput, the same verdict, and no more proof of
                     durability than the run above. See Honest limits
```

Power-off — lazyfs drops what was never fsynced, then SIGKILL:

```
all four           bank + register + set + counter, 18 power-offs, every
  power-off          verdict :valid? true

set power-off      1512 acknowledged writes, 0 lost, 5 power-offs. LMDB fsyncs
                   on commit, and the meta page holds

set power-off      four runs in five: MDB_INVALID on reopen -- the environment
  sync=off           is corrupt, the closing read never happens, :valid? :unknown.
                     The fifth reopened and had lost 1506 of 1518 acknowledged
                     writes. One env flag apart from the run above

set kill           1522 acknowledged, 0 lost, :valid? true -- that same
  sync=off           configuration, sailing through the fault that can't see it
```

## Layout

| file | what it is |
|---|---|
| `src/lmdb/native.clj` | finds liblmdb and points lmdbjava at it, before anything touches it |
| `src/lmdb/db.clj` | LMDB itself: the environment, the transactions, the operations. Shared by both shapes |
| `src/lmdb/in_process.clj` | the adapter that calls it directly, in Jepsen Lite's JVM |
| `src/lmdb/driver.clj` | the same operations behind HTTP — the process that gets killed |
| `src/lmdb/client.clj` | the adapter that speaks HTTP to that process |
| `src/lmdb/runner.clj` | the runner: both run configs, CLI, `-main` |

`native.clj`, `db.clj` and `driver.clj` depend on nothing but lmdbjava, and know
nothing about Jepsen Lite: the driver has to be runnable, and killable, on its
own.

```sh
clojure -M:driver --port 8080 --data-dir ./jepsen-data/manual --sync on
curl -X POST http://127.0.0.1:8080/bank/read -d '{}'
```

The `:power-off` runs need Linux, `/dev/fuse` and a built lazyfs; `cases/docker/` and
`cases/power-off` provide all three, and the container also
carries the system liblmdb this suite binds to.

lmdbjava reaches into `java.nio`'s internals to hand LMDB direct buffer
addresses without copying, so anything that opens an environment needs
`--add-opens java.base/java.nio=ALL-UNNAMED` and
`--add-opens java.base/sun.nio.ch=ALL-UNNAMED`. Both aliases carry them, and
`runner.clj` repeats them on the driver's command line — a freshly started JVM
inherits nothing from the run that starts it.

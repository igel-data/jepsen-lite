# Cases

Real stores, verified with Jepsen Lite.

| case | what it covers |
|---|---|
| [`sqlite/`](sqlite/) | SQLite — in-process, `SIGKILL`, and power loss |
| [`lmdb/`](lmdb/) | LMDB — in-process, `SIGKILL`, and power loss |

These are not part of the library. Each case is a project of its own, with its
own `deps.edn` and its own README, and depends on Jepsen Lite through
`{:local/root "../.."}` — so the arrow points one way only. Jepsen Lite's own
`deps.edn` knows nothing about SQLite or LMDB, and depending on the library
never drags in `sqlite-jdbc` or `lmdbjava`.

Run one from its own directory:

```sh
cd cases/sqlite && clojure -M:jepsen
cd cases/lmdb   && clojure -M:jepsen --profile process --workload set --fault crash
```

Because the dependency is the working copy rather than a release, a change to
`src/lite/` is under test the moment it is made.

## What a case is for

The demos under [`examples/`](../examples/) show the shape of an integration
against a store written to be demonstrated with. A case is the other thing: a
store nobody here controls, wired up honestly, and asked hard questions. Both of
these are embedded libraries, so each needs a small driver process before
anything can `kill -9` it — that driver is the case's own code, and it uses
nothing a consumer couldn't.

The shape they share is Jepsen Lite's own: the **protocol** a store is reached
by and the **way it is deployed** are separate axes, and the workloads, checkers
and verdicts are identical across both. So a case is one set of operations plus
one adapter per protocol.

## Power loss

`:crash` and `:kill` cannot test `fsync`. A SIGKILL kills the process, not the
kernel, so writes the store handed to the OS are written back regardless, and a
store that syncs what it acknowledges comes through a kill identically to one
that doesn't. **`:power-off` is the fault that asks**: Jepsen Lite mounts the
target's data directory on [lazyfs](https://github.com/dsrhaslab/lazyfs), clears
its cache — dropping precisely what was never fsynced — and then kills.

lazyfs is FUSE, so this is Linux only. `docker/` and `power-off` are the
shared way to get there from anywhere else — a Linux box with lazyfs built, plus
the native libraries the individual cases bind to:

```sh
cases/power-off sqlite --profile process --workload set --fault power-off
cases/power-off sqlite --profile process --workload set --fault power-off --sync off
cases/power-off lmdb   --profile process --workload set --fault power-off --sync off
```

Each case carries the measured contrast in its own README. In short: at
`synchronous=OFF`, SQLite loses 1238 of 1598 acknowledged writes to a power-off
and none at all to a kill. With `MDB_NOSYNC`, LMDB usually comes back *corrupt* —
`MDB_INVALID`, unopenable — and when it does reopen it has lost almost
everything it acknowledged; to a kill, again, it loses nothing. Configured to
sync, both survive every fault.

That last point is why these are worth having in the repository. `:power-off` is
the newest fault in the library, and two real stores that fail it exactly when
they should — and pass it exactly when they should — are the evidence that it
does what it claims.

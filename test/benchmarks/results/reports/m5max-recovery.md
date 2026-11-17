# Recovery: lost leases, a planned hand-over, and a catch-up under live orders

This report covers how a cluster comes back when a node loses its partitions, and when partitions move. It has three before/after pairs, one for each fix:

- **A node that loses all its leases at once** (#6379, fixed by #6384): one node on MongoDB loses all 64 leases when its store pauses. The comparison is the time until every partition is acquired again.
- **A planned hand-over** (#6407, fixed by #6412): CI's e3-handover step on three nodes. The comparison is the sagas' timed-out waits and the p99.
- **A catch-up on a migrated history under live orders** (D399's bound, at #6412): the migration rehearsal's live orders while the V10 host catches up on the imported log. The comparison is with two catch-up permits and with no bound.

The first two pairs ran on the local Apple M5 Max on 2026-11-17, each in one hold of the benchmark lock. The third ran on GitHub Actions the same day. Every JVM ran JDK 25. Each table states its builds and its machine.

Each pair is a build against the build before it, not a release. No number here is computed across two builds, and none is set beside another report's numbers.

The raw inputs, the scripts that produced them and [`summarize.py`](m5max-recovery/scripts/summarize.py) are in [`m5max-recovery/`](m5max-recovery/). `summarize.py --json` writes `summary.json`, and `headline.json` is generated from it.

## Machines

| | |
| --- | --- |
| Local | Apple M5 Max, 18 cores, 64 GB, macOS 27.2.1; Docker engine 29.8.2 (OrbStack) with 16 CPUs and 48,158 MiB; Temurin 25.0.5+8 for the nodes and the load generator (each round's `result.json`) |
| Actions | `migration-rehearsal.yml` on the `ubuntu-26.04` runner image, both jobs (runner and kind). The hosts ran Java 25.0.5 (`jvm.txt`). The runs do not record the CPU model |

## A node that loses all its leases at once (#6379)

**Setup** ([`lost-leases-run.sh`](m5max-recovery/scripts/lost-leases-run.sh)):
- One node on MongoDB with 1,000 accounts, and cluster logging at info.
- The load is a 45 s E1 pre-load, then the CI mix (E1 50%, E4-state 40%, E4-list 10%) at 760/s, with 10 s of warm-up and 60 s measured.
- 15 s into the measurement, MongoDB is paused for 10 s, then served at 0.25 CPU for 30 s: slow, but not stopped.
- Three rounds a side, alternating base and fix.
- In every round the node lost all 64 leases 5.4 to 7.8 s into the pause.

**Builds:**
- fix `93f53932fa` (#6384's head);
- base `7f46c8dfb4` (its merge base).

Both are clean builds (`install.txt`: `dirty=no`).

The times are from each partition's loss (the node's `has not renewed the lease of` or `lost the lease of` line) to its acquisition (`acquired partition`), read from `node-1.log`.

| Build | Round | Back of 64 at the round's end | First back | Last back |
| --- | ---: | ---: | ---: | ---: |
| fix `93f53932fa` | 1 | 64 | 2.4 s | 2.4 s |
| fix `93f53932fa` | 2 | 64 | 2.6 s | 3.5 s |
| fix `93f53932fa` | 3 | 64 | 2.7 s | 2.9 s |
| base `7f46c8dfb4` | 1 | 16 | 47.6 s | not reached |
| base `7f46c8dfb4` | 2 | 28 | 47.2 s | not reached |
| base `7f46c8dfb4` | 3 | 16 | 47.8 s | not reached |

- **With the fix,** all 64 partitions were back 2.4, 3.5 and 2.9 s after the loss, at the unpause.
- **On the base,** none was back before 47.2 s. Its last partition's time is above 48 s and was not reached within the round.
- **Why the base is slow:** it stopped the lost partitions one after another and acquired them only after the last stop. Each stop waited for drains that ran against the throttled store, because the lost partitions' commands ran without the lease meanwhile (D6, D71).
- **With the pause alone** (5 s, store back at once), both arms were back within 1.5 s. That earlier run is not part of this report.

## A planned hand-over (#6407)

**Setup** ([`handover-all.sh`](m5max-recovery/scripts/handover-all.sh)), CI's e3-handover step:
- Three nodes and 10,000 accounts, with E3 at 50/s: 15 s of warm-up and 90 s measured.
- Node 2 is stopped gracefully 25 s into the measurement and restarted 20 s later.
- MongoDB rounds alternate main and fix, three rounds each; then one PostgreSQL round with the fix.

**Builds:**
- main `e3a4e937aa`;
- fix `55937a4b2c`: #6412's first commit before a rebase. It has the same change as `9a5c637812` (`git patch-id`), which merged in `60c63cdc6f`.

Both trees carry log probes only ([`handover-probe.py`](m5max-recovery/scripts/handover-probe.py), [`handover-move.patch`](m5max-recovery/scripts/handover-move.patch)): `install.txt` says `dirty=yes`. Both ran at the default bound, two catch-up permits.

Each failed command is a saga's timed-out wait (`504 wow.wait.timeout`), out of 5,250 sent a round (`result.json`).

| Build | Store | Failed waits per round | p99 per round |
| --- | --- | --- | --- |
| main `e3a4e937aa` | MongoDB | 15, 2, 2 | 5,636.1 / 2,867.2 / 2,850.8 ms |
| fix `55937a4b2c` | MongoDB | 1, 1, 2 | 1,982.5 / 1,581.1 / 1,900.5 ms |
| fix `55937a4b2c` | PostgreSQL | 0 | 21.8 ms |

- **With the fix,** every moved partition caught up within its first page, without a permit (the probes' `[catchup]` lines).
- **On main,** the moved partitions queued two at a time for the node's permits. Each catch-up held its permit about 2 s, because MongoDB's tail waits its 1 s stall grace at each foreign open block.
- **What remains with the fix** is that stall grace. One partition took 11.4 s to catch up and gave fix-1's timed-out wait.

## A catch-up under live orders (D399's bound, at #6412)

**Setup:** `migration-rehearsal.yml` on two throwaway `ci-repro` branches. Each is #6412's head `f33284fa69` plus the instrumentation of D399's rehearsal, ported from `62ea68f0f9` ([`rehearsal-port.py`](m5max-recovery/scripts/rehearsal-port.py)):
- a `driver live` step: 50 orders a second for 120 s on the V10 host's first start on the imported log (50,000 orders), each waiting `committed` with a 10 s limit;
- the hosts' `TIMING` lines.

**Arms:**
- two permits (`--wow.subscription.max-catch-ups=2`): `0853cbf27b`, run 44137290807;
- no bound (`=0`): `7c0896effd`, run 44137289607.

The sources are each job's `offline-live.log` (totals) and `offline-live.jsonl` (one line a second, by completion). The catch-up is example-service's `TIMING caughtUp`: from the host's join until every position is at lag 0 (`timing.txt`).

| Permits | Job | Failed of 6,000 | p99 | Worst second's p99 | Catch-up |
| --- | --- | ---: | ---: | ---: | ---: |
| 2 | runner | 0 | 7,922 ms | 10,260 ms | 87.5 s |
| 2 | kind | 0 | 3,929 ms | 4,614 ms | 59.4 s |
| no bound | runner | 7 | 16,814 ms | 22,566 ms | 66.3 s |
| no bound | kind | 2,587 | 30,011 ms | 30,475 ms | 107.2 s |

- **The bound holds:** with two permits no live order failed, on either job.
- **With no bound,** 7 and 2,587 failed:
  - on runner: all `504 wow.wait.timeout`;
  - on kind: 2,328 `504 wow.wait.timeout` and 259 client timeouts.
- **The bound refuses no command.** It only holds a partition's reads past its first page until a permit is free.
- **Not measured: main at two permits in the same window.** The worst seconds' p99 with two permits, 10.3 s on runner and 4.6 s on kind, is above the 2.0 to 3.7 s that D399's runs recorded at an earlier commit. With no main arm, this run cannot separate #6412's first page from runner variance.
- **Not measured: how long each partition held a permit.** The `TIMING` lines are per host.

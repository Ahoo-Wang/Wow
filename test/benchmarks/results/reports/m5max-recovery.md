# Recovery: lost leases, a planned hand-over, and a catch-up under live orders

This report covers how a cluster comes back when a node loses its partitions, and when partitions move. It has before/after pairs for each fix, and the code that ships is the last build in each:

- **A node that loses all its leases at once** (#6379, fixed by #6384): one node on MongoDB loses all 64 leases when its store pauses. The comparison is the time until every partition is acquired again.
- **A planned hand-over** (#6407, fixed by #6412, then by #6424): CI's e3-handover step on three nodes. The comparison is the sagas' timed-out waits and the p99.
- **A catch-up on a migrated history under live orders** (D399's bound, at #6412): the migration rehearsal's live orders while the V10 host catches up on the imported log. The comparison is with two catch-up permits and with no bound.
- **A node's first activation on a migrated history** (#6412 against its parent, then #6424 against the same parent): the same live orders at two permits. #6412's rows are the reason for #6424; #6424 is the code that ships.

The lost-leases and hand-over pairs ran on the local Apple M5 Max on 2026-11-17 and 2026-11-18, each in one hold of the benchmark lock. The rehearsal rows ran on GitHub Actions on the same days. Every JVM ran JDK 25. Each table states its builds and its machine.

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

**The code that ships** ([`handover-gate.sh`](m5max-recovery/scripts/handover-gate.sh)). #6424 replaced #6412's free first read, so the same step was run again, on 2026-11-17 from 23:57 to 00:15Z (2026-11-18), in one hold of the lock:
- main `f43a7a75bf`, which carries #6412, against #6424's first commit `0249db185b`, three rounds each alternating;
- then one PostgreSQL round with `0249db185b`.

Both are clean builds without probes (`install.txt`: `dirty=no`). #6424 merged as `bc220111ba`. Its second commit changed no behaviour: it kept a helper and added a test.

| Build | Store | Failed waits per round | p99 per round |
| --- | --- | --- | --- |
| main `f43a7a75bf` (#6412) | MongoDB | 1, 1, 1 | 1,900.5 / 1,974.3 / 1,777.7 ms |
| #6424 `0249db185b` | MongoDB | 1, 1, 1 | 1,810.4 / 1,900.5 / 1,736.7 ms |
| #6424 `0249db185b` | PostgreSQL | 0 | 19.7 ms |

- **#6424 keeps what #6412 gave the hand-over.** The moved partitions' gaps fit in its 512, so they read at once, as with #6412.
- **The one failed wait a round on MongoDB** is the same stall grace, in both arms.

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
- **Main at two permits** is measured in the next section, by pairs in one window.
- **Not measured: how long each partition held a permit.** The `TIMING` lines are per host.

## A node's first activation on a migrated history (#6412, then #6424)

**Setup:** the same rehearsal step, with the same instrumentation, at two permits. Each pair was dispatched together, so its two runs share a window. A pair's arms are the code below, each plus that instrumentation, on a throwaway `ci-repro` branch whose commit the run built.

**Arms:**
- parent `0d2017aa28`, the commit before #6412 (built as `95bd118e96`);
- #6412 `60c63cdc6f` (built as `17ce2d07ff`);
- #6424 `0249db185b` (built as `6781a666aa`).

The sources are the same files as in the last section, under `first-start/<run>[-a<attempt>]/<job>`.

**#6412 against its parent**, the reason for #6424. The runs are 44194947161 (parent) and 44194947617 (#6412). The second pair is attempt 2 of the same runs.

| Pair | Build | Job | Failed of 6,000 | p99 | Worst second's p99 | Catch-up |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 1 | parent `0d2017aa28` | runner | 0 | 2,704 ms | 4,623 ms | 85.2 s |
| 1 | #6412 `60c63cdc6f` | runner | 0 | 6,722 ms | 8,283 ms | 89.4 s |
| 1 | parent `0d2017aa28` | kind | 0 | 1,134 ms | 1,937 ms | 51.0 s |
| 1 | #6412 `60c63cdc6f` | kind | 0 | 2,495 ms | 3,775 ms | 63.6 s |
| 2 | parent `0d2017aa28` | runner | 0 | 2,259 ms | 3,261 ms | 91.3 s |
| 2 | #6412 `60c63cdc6f` | runner | 0 | 1,096 ms | 1,752 ms | 60.9 s |
| 2 | parent `0d2017aa28` | kind | 0 | 3,422 ms | 4,111 ms | 94.2 s |
| 2 | #6412 `60c63cdc6f` | kind | 0 | 3,985 ms | 5,205 ms | 49.0 s |

- **In the first pair** #6412's worst second was 1.8 to 1.9 times its parent's on both jobs. In the second pair the direction differed by job.
- **No direction holds across pairs,** so these runs neither show that #6412 is worse at a first activation nor that it is equal.
- **The reason for #6424** is what the bound is for. #6412 let every partition behind its head read 512 entries without a permit, and that includes a node's first activation on an imported history, which is the case D399's bound is written for.
- **The parent's red runner jobs** (both attempts) are its `rollback-after-compare`, 25 minutes after this step. It read V10 while the host was still catching up and did not wait; the export and 9.x were right.

**#6424 against the same parent**, the code that ships. Two pairs, dispatched together: 44203434208 (parent) with 44203434241 (#6424), and 44203470768 (parent) with 44203470883 (#6424).

| Pair | Build | Job | Failed of 6,000 | p99 | Worst second's p99 | Catch-up |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 1 | parent `0d2017aa28` | runner | 0 | 1,012 ms | 1,640 ms | 49.5 s |
| 1 | #6424 `0249db185b` | runner | 0 | 2,413 ms | 3,278 ms | 49.5 s |
| 1 | parent `0d2017aa28` | kind | 0 | 1,426 ms | 2,458 ms | 49.1 s |
| 1 | #6424 `0249db185b` | kind | 0 | 2,058 ms | 3,471 ms | 84.7 s |
| 2 | parent `0d2017aa28` | runner | 0 | 1,868 ms | 2,713 ms | 73.2 s |
| 2 | #6424 `0249db185b` | runner | 0 | 1,278 ms | 2,092 ms | 45.1 s |
| 2 | parent `0d2017aa28` | kind | 0 | 1,527 ms | 2,529 ms | 49.3 s |
| 2 | #6424 `0249db185b` | kind | 0 | 1,514 ms | 2,209 ms | 49.6 s |

- **No live order failed** in any run.
- **#6424's worst seconds** are 2.1 to 3.5 s. The parent's are 1.6 to 2.7 s in this window and 1.6 to 4.6 s over all eight of its job runs. They are within D399's 2.0 to 3.7 s.
- **What #6424 does here:** every partition of the imported log is thousands of entries behind its head, so it waits for a permit before its first entry, as on the parent.
- **One #6424 catch-up took 84.7 s** (kind, pair 1). It is the one outside the 45 to 73 s of the other runs in this window, and within the 85 to 94 s the parent took in three of its four job runs in the pairs above.

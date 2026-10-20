# V10 on MongoDB: store operations per command (B.D diagnostics, low load)

**B.D diagnostics (decision D224), measured at low load. Not a benchmark.** This report counts store operations per E1 `Deposit`. It does not measure latency or throughput. The rate is 20 operations/s, so the fanless host does not throttle. Nothing here supports a claim about saturation. It explains the operation counts the B.0 smoke run reported (V10 on MongoDB: about 7 updates and 8 commands per `Deposit`; 9.x: about 1 update and 0.1 commands) and fixes what could be avoided without changing a guarantee.

## Setup

- Before: commit `dd29ad68b` (branch `v10-bench-diag-mongo` at `origin/v10` `acd3dccb5`, no fix). After: commit `400de9f30`. 2026-10-16.
- Apple M4 MacBook Air, 10 cores, 16 GB, macOS 27.0.1; Docker Desktop 29.8.2: `mongo:7.0` (a one-node replica set), `postgres:17-alpine` (with `pg_stat_statements` loaded), `confluentinc/cp-kafka:7.4.0`. These are the harness's `compose.yml` plus [`tools/override.yml`](v10-mongo-write-amplification/tools/override.yml).
- One server node, Temurin 21.0.12.1, `-Xms1g -Xmx1g -XX:+UseG1GC`. Topologies as in [`wow-benchmarks/e2e/README.md`](../../e2e/README.md). V10 runs with 64 partitions (its default).
- Each run starts on empty containers and seeds 1,000 accounts. It then has an **idle window** of 30 s, which captures the periodic work alone, and a **load window**: E1 at 20/s, open loop, uniform keys (seed 20261015), 0 s warm-up, 30 s, 600 deposits, plus 5 s to drain. Every `Deposit` completed on every run.
- MongoDB: the profiler at level 2 on the service's database (every operation, with collection and kind) and the `serverStatus` command counters (round trips by command name, including `commitTransaction`). PostgreSQL: `pg_stat_statements` and `pg_stat_user_tables`.
- **Per command** means the load window's count, minus the idle window's rate times the load window's length, divided by 600. Periodic work is reported apart, as a rate.

Raw results: [`before/`](v10-mongo-write-amplification/before/) and [`after/`](v10-mongo-write-amplification/after/). Each setup has its profile and counter snapshots, the load generator's `summary.md` and `result.json`, and `per-command.txt` (the output of `tools/analyze.py`).

## Summary

| Setup | Round trips per `Deposit` before → after | Operations (documents or statements) per `Deposit` before → after |
| --- | --- | --- |
| 9.x, MongoDB | 4.00 → 4.00 | 4.00 → 4.00 |
| V10, MongoDB | **18.24 → 6.62** | 17.64 → 12.16 |
| V10, PostgreSQL (on its own, D224) | about 14.4 → about 3.5 | 14.38 → 10.17 statements |

The two columns differ for one reason. A tick's checkpoints are now saved in one round trip (a bulk write or a JDBC batch). That batch still writes one document or row per subscription and partition whose checkpoint moved, and the profiler and `pg_stat_statements` count each of those.

**What the B.0 smoke numbers were.** Two kinds of work accounted for most of V10's "7 updates and 8 commands per `Deposit`", and neither grows with the number of commands:

- **Periodic checkpoint saves.** Each of the 8 subscriptions saves its checkpoint in each partition whose checkpoint moved, once a second. At 20 to 50 commands/s, spread over 64 partitions, that is about 7 saves per command. It stays bounded by 8 × 64 = 512 saves/s whatever the load.
- **The tail backing off again after every commit.** At low load each commit cost about 5 reads of the partition.

At saturation both shrink per command, so they do not explain a lower saturation point. The per-command work that does grow with load is listed under "What V10 does per command now".

## Before and after, by collection and kind

### V10, MongoDB (profiled operations per `Deposit`, idle removed)

| Collection | Kind | Before | After | Issued by | Per | Class |
| --- | --- | --- | --- | --- | --- | --- |
| `wow_log__transfer__account` | find | 1.00 | 1.00 | the kernel's request-id lookup before deciding (`Kernel.executeAdmitted` → `findByRequestId`) | command | (a) D46 L2 |
| `wow_log__transfer__account` | find, in the transaction | 1.00 | 1.00 | append: read the aggregate's version (`MongoEventLog.appendNow`) | command | (a) D11 |
| `wow_log__transfer__partition` | findAndModify, in the transaction | 1.00 | 1.00 | append: `$inc` the partition counter | command | (a) D11 |
| `wow_log__transfer__account` | insert, in the transaction | 1.00 | 1.00 | append: the stream | command | (a) D11 |
| (admin) | commitTransaction | 1.00 | 1.00 | append: the commit | command | (a) D11 |
| `wow_snapshot__transfer__account` | update → findAndModify | 2.00 | 1.00 | the `snapshot` subscription's save: a guarded upsert, **then an update clearing a stale mark that almost never exists** | command (coalesced per aggregate within a batch) | 1 (a) snapshot cache, 1 **(b) fixed** |
| `wow_log__transfer__partition` | aggregate | 4.75 | 0.52 | the shared tail of the partition (`MongoEventLog.readTail`). **After each commit it polled every 50 ms and backed off again** | commit (and periodic, 64/s idle) | **(b) fixed**; the remaining read is (a) A2-14 |
| `wow_checkpoint` | update | 6.74 (6.74 round trips) | 6.92 (0.05 round trips) | the subscription runtime's periodic save: **one round trip per subscription and partition** | periodic, 1 s | **(b) fixed** (round trips); the documents are (c), see below |
| `wow_lease` | findAndModify | 0.20 (21/s) | −0.04 (21/s) | partition lease renewals (`PartitionOwnership.renew`) | periodic, per partition | (a) D45, D71 |
| `wow_log__transfer__partition` | find | −0.04 (12.8/s idle) | 0 (0 once caught up) | snapshot maintenance reading every partition's head on every run (`SnapshotMaintenance.heads`) | periodic | **(b) fixed** |
| `wow_lease_member`, `wow_lease`, `wow_timer__transfer`, `wow_failure_ledger`, `wow_snapshot_table` | find, update | 0 (about 6/s) | 0 (about 6/s) | membership heartbeat and reconcile, the timer poll, the parked-entry scan, the snapshot table claim | periodic | (a) D71, D172; timers and ledger as designed |
| **Total** | | **17.64** | **12.16** | | | |

Server command counters (round trips) per `Deposit`, idle removed:

| Command | Before | After | 9.x |
| --- | --- | --- | --- |
| `find` | 1.95 | 1.99 | 2.00 |
| `insert` | 1.00 | 1.00 | 1.00 |
| `findAndModify` | 0.96 | 2.07 | 0 |
| `commitTransaction` | 1.00 | 1.00 | 0 |
| `update` | 8.74 | 0.05 | 1.00 |
| `aggregate` | 4.59 | 0.51 | 0 |
| **Total** | **18.24** | **6.62** | **4.00** |

### 9.x, MongoDB (unchanged code, measured before and after: identical)

| Collection | Kind | Per `Deposit` | Issued by |
| --- | --- | --- | --- |
| `account_snapshot` | find | 1.00 | loading the aggregate: its snapshot (9.x keeps no state between commands) |
| `account_event_stream` | find | 1.00 | loading the aggregate: the events after the snapshot (none) |
| `account_event_stream` | insert (retryable write, no transaction) | 1.00 | the append; the unique indexes on `(aggregateId, version)` and `(aggregateId, requestId)` stand in for a version read and a request check |
| `account_snapshot` | update | 1.00 | the snapshot, strategy `all` |
| periodic | | 0/s | nothing: the bus is Kafka, with no leases, checkpoints or tails on MongoDB |

Things 9.x does not do at all:

- a request-id lookup before deciding (it relies on the unique index at insert);
- a transaction (no partition sequence);
- a tail of the log, checkpoints or leases on the store (Kafka's consumer groups do that work).

V10, for its part, skips 9.x's two loading reads, because its state cache stays warm between commands.

### V10, PostgreSQL (statements per `Deposit`, idle removed)

| Statement | Before | After | Class |
| --- | --- | --- | --- |
| request-id lookup (`SELECT ... WHERE tenant_id = ? AND request_id = ?`) | 1.00 | 1.00 | (a) D46 L2 |
| the append, one autocommitted statement (version check, counter bump and insert in one CTE) | 1.00 | 1.00 | (a) D11 |
| snapshot upsert (already one statement) | 1.00 | 1.00 | (a) |
| tail read (lateral join over the partition counter) | 4.62 | 0.49 | **(b) fixed** |
| checkpoint upsert | 6.86, one statement and one commit each | 6.98, in one JDBC batch a tick | **(b) fixed** (round trips) |
| lease renewals, membership, timers, ledger, partition heads | periodic | periodic, heads gone once caught up | (a); heads **(b) fixed** |
| **Total** | **14.38** | **10.17** | |

Transactions committed in the load window (`pg_stat_database.xact_commit`, idle included): 12,738 before, 5,724 after.

## What V10 does per command now (MongoDB)

On a warm aggregate, one `Deposit` costs:

- a request-id lookup (D46);
- an append transaction of three statements and a commit (D11);
- a snapshot save (one round trip);
- about half a tail read.

That is 6 to 7 round trips, against 9.x's 4. On top of that come 512 checkpoint documents per second at most, in 1 round trip a second, and the lease renewals (about 21/s on 64 partitions). Two integration tests pin these counts: `MongoEventLogTest.an append to a warm aggregate is one transaction of three statements` and `MongoSnapshotStoreTest.a save is one round trip unless the snapshot is marked stale`.

## Fixes (class b)

Each fix is a separate commit on this pull request:

| Fix | Saving at 20/s | Test that pins the count |
| --- | --- | --- |
| Snapshot save on MongoDB. It is a `findAndModify` returning the stored stale mark, and the clearing update runs only when that mark is at or below the saved version. As a side effect, a mark set after the write, of the version it wrote, is no longer cleared by that write. | 1 update per command | `MongoSnapshotStoreTest.a save is one round trip unless the snapshot is marked stale`, plus the snapshot TCK |
| Tail pacing on MongoDB and PostgreSQL. A poll that this log's own commit woke, and that found entries without being behind the counter, keeps the backoff. Entries found without a wake still bring the tail back to the poll interval. Other nodes' entries are still read within `maxPollInterval` (A2-14). | about 4.2 tail reads per command | `MongoEventLogTest` and `PostgresEventLogTest`, test `a commit of this log costs its idle tail one read` (exactly 1 read per commit); `TailPacerTest` |
| Checkpoints. `CheckpointStore.saveAll` (an additive SPI method with a default) saves every moved checkpoint of a tick at once: one unordered bulk write on MongoDB, one JDBC batch in key order on PostgreSQL. Each checkpoint still only moves forward (D45). | about 6.7 round trips per command | `MongoCheckpointStoreTest.saving the checkpoints of a tick is one round trip`; `SubscriptionCheckpointTest.the periodic save stores every moved checkpoint in one call, ...`; the checkpoint TCK (two new cases, every backend) |
| Snapshot maintenance reads the partition heads only while a target has not caught up. | 64 reads per run (about 11/s) when idle | `SnapshotMaintenanceTest.a run reads the partition heads only while a target has not caught up` |

## Avoidable only by changing a decision (class c), and other points for the user

| Item | Cost | Decision it touches | Recommendation |
| --- | --- | --- | --- |
| Skip the request-id lookup for decides without I/O (D46's L1 rotating Bloom filter) | 1 find per command, on both backends | D46 allows it but it is not implemented. When the filter misses (after a restart, an owner change or a rotation), the append's unique key still catches a duplicate. But a retried command whose rule now rejects it (a `Withdraw` once the balance is spent) is answered with the rejection instead of the original commit. | Worth doing before the rerun if the user accepts that edge. Sizing: a filter per partition owner, in memory, and a way to mark a decide as pure. |
| Drop the version read inside the append transaction, relying on the unique `(aggregate, tenant, version)` index | 1 find per command (MongoDB) | The log SPI's guarantee that an append at a version other than current + 1 conflicts (no gaps); D11 as written. A unique index cannot refuse a gap. | Keep it. The kernel's own invariants make a gap unlikely, but this guarantee is what makes a stale snapshot or cache safe. |
| One checkpoint document per (group, partition) instead of one per subscription | up to 512 document writes/s (now in 1 round trip/s); about 7 documents per command at 20/s, at most 0.2 at 2,500/s | A storage-format change of `wow_checkpoint` (format version, D59) | Not worth it now that it is one round trip. A longer `checkpointInterval` is the cheaper knob, at the cost of a longer replay after a crash. |
| Renewing a node's leases in fewer round trips | about 21 findAndModify/s on 64 partitions, idle or loaded | The `LeaseStore` SPI and the renewal round (D45, D71, spike H6) | Periodic, not per command; a follow-up only if the idle cost of a large cluster matters. |
| The append's transaction itself (snapshot read concern, majority commit) | 4 of the 6 to 7 round trips per command | D11 (the transactional partition sequence); H1 measured MongoDB at about one sixth of PostgreSQL's append rate | This is the inherent V10 on MongoDB cost. D11's group commit amortizes the counter and commit across a partition's batch under load. B.1's rerun should confirm how much of the saturation gap remains after these fixes. |

## Reproducing it

From the repository root, with Docker and `JAVA_HOME` set to a JDK 21:

```bash
wow-benchmarks/e2e/harness/bench.sh build
T=wow-benchmarks/results/reports/v10-mongo-write-amplification/tools
$T/measure.sh v10 mongo    /tmp/bd/v10-mongo      # rate 20/s, 30 s, 1,000 accounts by default
$T/measure.sh v10 postgres /tmp/bd/v10-postgres
$T/measure.sh 9x  mongo    /tmp/bd/9x-mongo
python3 $T/analyze.py /tmp/bd/v10-mongo /tmp/bd/v10-postgres /tmp/bd/9x-mongo
```

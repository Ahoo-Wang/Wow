# V10 against 9.x: what each operation costs, counted (E1 to E3, low load)

**The count-based tier of decision D224 (implementation note of 2026-10-19): counts, not timings.** Store round trips, store operations, Kafka messages and heap allocations do not depend on the machine's load, so these were measured at a low rate (20 operations/s) on a shared machine. Nothing here says anything about latency, throughput or saturation; those wait for the time-based tiers on a machine of their own (`wow-benchmarks/e2e/harness/tiers.sh`). E4 (reads) and E5 (cluster) are not measured: their designs are changing (R3-A/R3-B, R3-H).

## Setup

- 2026-10-19, Apple M4 Pro, 14 cores, macOS 27; Docker Desktop: `mongo:7.0` (one-node replica set), `postgres:17-alpine` with `pg_stat_statements`, `confluentinc/cp-kafka:7.4.0` (`harness/compose.yml` plus `harness/compose.counts.yml`). One server node, Corretto 21.0.12.1, `-Xms1g -Xmx1g -XX:+UseG1GC`.
- **V10**: branch `v10-bench-b1` at `origin/v10` `4251e3463` (merged as `18443a03e`), framework code unchanged by this branch; the harness commits measured are in each setup's `raw/<setup>/config.txt` (`19ffd3785` for V10 MongoDB, `e2e4ffe77` for the others; they differ in benchmark files only). Partitions: 64 (the default).
- **9.x**: the 9.x modules of the same tree, `v9.9.0` plus the 9 commits of `main` up to `5fa1d4090` (2026-10-12) that the last forward merge brought in, built from source.
- Each setup: empty containers, 1,000 seeded accounts, then an **idle window** of 60 s (the periodic work alone), then one **load window** per scenario: 20/s open loop for 60 s, uniform keys, plus 5 s for the chain to finish. 1,200 operations per window, all completed, none failed.
- **Per operation** = (the window's count − the idle rate × the window's length) / 1,200. Round trips are what the store answered: MongoDB's `serverStatus` command counters (data commands only) and its profiler for the kind; PostgreSQL's `pg_stat_statements`. Operations are what the store did (one per profiled document write or statement), so a batched checkpoint save is one round trip and many operations. Allocations: the server JVM's `ThreadMXBean.getTotalThreadAllocatedBytes`, read before and after each window over a local JMX connection.
- Reproduce: `harness/counts.sh scenarios <setup> <out>` per setup, then `harness/counts.py scenarios <out>`. Raw windows in [`v10-counts-e1-e3/raw/`](v10-counts-e1-e3/raw/), the computed numbers in [`counts.json`](v10-counts-e1-e3/counts.json).

## Round trips per operation, by kind

| Kind | E1 9.x Mongo | E1 V10 Mongo | E1 V10 PG | E2 9.x Mongo | E2 V10 Mongo | E2 V10 PG | E3 9.x Mongo | E3 V10 Mongo | E3 V10 PG |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| request-id check |  | 1.00 | 1.00 |  | 1.00 | 1.00 |  | 3.00 | 3.00 |
| aggregate load | 2.00 |  |  | 2.00 |  |  | 6.00 |  |  |
| log append | 1.00 | 4.00 | 1.00 | 1.00 | 4.00 | 1.00 | 3.00 | 12.00 | 3.00 |
| snapshot | 1.00 | 1.00 | 1.00 | 1.00 | 1.00 | 1.00 | 3.00 | 3.00 | 3.00 |
| state read |  |  |  | 1.00 | 1.00 | 1.00 |  |  |  |
| tail |  | 0.57 | 0.51 |  | 0.58 | 0.52 |  | 2.13 | 2.09 |
| checkpoint |  | 0.05 | 0.06 |  | 0.05 | 0.06 |  | 0.05 | 0.06 |
| lease |  | 0.03 | −0.01 |  | −0.02 | −0.01 |  | −0.01 | −0.01 |
| **Round trips** | **4.00** | **6.64** | **3.56** | **5.00** | **7.61** | **4.57** | **12.00** | **20.18** | **11.14** |
| Store operations | 4.00 | 13.49 | 10.37 | 5.00 | 14.41 | 11.42 | 12.00 | 32.05 | 22.95 |
| Kafka messages | 3.00 | 1.00 | 1.00 | 3.00 | 1.00 | 1.00 | 9.00 | 3.00 | 3.00 |
| Allocated KiB | 391.6 | 386.5 | 126.4 | 461.9 | 446.3 | 159.8 | 1,010.0 | 1,055.0 | 319.7 |

PostgreSQL is reported on its own and not compared with 9.x (D224). The PostgreSQL checkpoint round trips are an upper bound: a tick's checkpoint upserts are one JDBC batch, at most one round trip a second (`pg_stat_statements` counts the statements, about 6.9 per E1 operation). Lease rows near zero are periodic work that the idle window already removed.

What each kind is:

| Kind | 9.x | V10 |
| --- | --- | --- |
| request-id check | none: a unique index on `(aggregateId, requestId)` catches a duplicate at insert | a read of the log by request id before deciding (D46) |
| aggregate load | the snapshot by id, then the events after it (9.x keeps no state between commands) | none: the state cache stays warm |
| log append | one insert (a retryable write, no transaction) | MongoDB: a transaction of a version read, a partition counter `$inc`, the insert and the commit (D11); PostgreSQL: one statement |
| snapshot | an update | MongoDB: a `findAndModify`; PostgreSQL: an upsert |
| state read (E2) | the snapshot by aggregate and tenant | the snapshot by id |
| tail, checkpoint, lease | none: Kafka's consumer groups do this work | the partition's shared tail, the subscriptions' checkpoints (one batch a second), the partition leases |

**E3** is one `Prepare`, the saga's `Entry` on the target and the saga's `Confirm` on the source: three commands, all three counted (the window waits 5 s after the last request, so the `Confirm` the response does not wait for is in it). 9.x sends each command through Kafka, with its event stream and state event: 3 messages per command. V10 routes commands in process (one node) and publishes the state stream: 1 message per commit.

## Periodic work (idle window)

| Per second | 9.x Mongo | V10 Mongo | V10 PG |
| --- | ---: | ---: | ---: |
| tail polls (64 partitions, backed off to 1 s) | 0 | 62.9 | 63.9 |
| lease renewals and membership | 0 | 24.9 | 24.8 |
| timers, parked ledger, snapshot table | 0 | 2.0 | 2.0 |
| **Round trips** | **0** | **89.8** | **90.8** |
| Heap allocated (MiB/s) | 0.26 | 4.74 | 0.93 |

This is what PS-2 measures per partition ([`v10-ps2-partition-idle.md`](v10-ps2-partition-idle.md)).

## Findings

- **The numbers match B.D after its fixes.** V10 on MongoDB costs 6.6 round trips per deposit against 9.x's 4.0; the gap is the append transaction (4 round trips where 9.x has 1 insert) plus the request-id check, partly offset by V10's warm state (no load reads). On PostgreSQL the append is one statement and V10 costs 3.6 round trips.
- **Per transfer (E3)**, V10 MongoDB costs 20.2 round trips against 9.x's 12.0, and V10 sends a third of 9.x's Kafka messages (3 against 9).
- **Allocation on MongoDB is three times PostgreSQL's** for the same work: 386 KiB per deposit against 126 KiB, and 4.7 MiB/s idle against 0.9 MiB/s. Idle, the same 90 round trips a second (mostly tail polls, an `aggregate` on MongoDB) allocate about 54 KiB each on MongoDB and about 10 KiB on PostgreSQL. V10 MongoDB and 9.x allocate about the same per deposit. Worth a profile (JFR allocation samples) before the timing tiers; it is GC pressure, not round trips. Not changed here: the framework is out of this work package's scope.

# V10 vs 9.x end-to-end benchmark: smoke run (not a benchmark)

**This is a smoke run, the exit check of work package B.0 (decision D224). It shows that both servers start in their production topology and that the load generator drives E1 end to end against each, with its results and resource capture. It is not a measurement: one short run per setup at a low fixed rate, far below either server's saturation, on one shared machine. Draw no comparison from it; B.1 to B.4 measure, B.R reports.**

## Setup

- Commit `04bd3c968933c45b356213d779636d62062615d3` (branch `v10-bench-b0`), clean tree, 2026-10-16.
- Apple M4, 10 cores, 16 GB, macOS 27.0.1; Docker Desktop 29.8.2 (10 CPUs, 7.7 GiB): `mongo:7.0` (one-node replica set), `postgres:17-alpine`, `confluentinc/cp-kafka:7.4.0` (one KRaft broker).
- Servers and load generator on Temurin 21.0.12.1; each server one node, `-Xms1g -Xmx1g -XX:+UseG1GC`.
- E1: a `Deposit` on one of 1,000 seeded accounts, uniform keys (seed 20261015), answered once stored (9.x `PROCESSED`, V10 `committed`); open loop at 50 operations/s, 10 s warm-up, 20 s measured. Topologies and completion points: [`wow-benchmarks/e2e/README.md`](../../e2e/README.md).
- Before each run the harness seeded the accounts and checked one operation of every scenario (E1, E2, E3, E4 by id, E4 list): all completed on all three setups.

## Results (smoke)

| Setup | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 9.x, MongoDB + Kafka | 50.0/s | 1000 | 0 | 9.5 ms | 14.0 ms | 16.2 ms | 20.3 ms | 20.3 ms | 20.3 ms |
| V10, MongoDB + Kafka | 50.0/s | 1000 | 0 | 8.0 ms | 12.6 ms | 14.5 ms | 25.4 ms | 25.4 ms | 25.4 ms |
| V10, PostgreSQL + Kafka (on its own, D224) | 50.0/s | 1000 | 0 | 6.8 ms | 10.8 ms | 12.3 ms | 14.2 ms | 14.2 ms | 14.2 ms |

With 1,000 operations a run, p999 is the maximum.

| Setup | Server CPU (cores) | Server RSS max | Heap used max | GC (count, time) | Store container CPU avg | Kafka CPU avg |
| --- | --- | --- | --- | --- | --- | --- |
| 9.x, MongoDB | 0.45 | 1066 MB | 679 MB | 1, 0.003 s | 22.9% | 53.1% |
| V10, MongoDB | 0.43 | 1021 MB | 591 MB | 1, 0.016 s | 36.0% | 29.5% |
| V10, PostgreSQL | 0.34 | 722 MB | 341 MB | 0 | 11.5% | 27.2% |

Store operations in the measured phase (about 1,000 deposits):

- 9.x, MongoDB: 986 inserts, 1,972 queries, 986 updates, 116 commands; Kafka: about 960 records on each of the command, event and state topics.
- V10, MongoDB: 987 inserts, 2,333 queries, 7,116 updates, 7,978 commands; Kafka: 962 records on the state topic.
- V10, PostgreSQL: 15,525 commits, 1,088 rows inserted, 8,267 updated, 13,886 fetched; Kafka: 971 records on the state topic.

What the smoke run shows: every setup started, seeded, completed every scenario once, and kept up with the schedule (the largest send lag was 7.5 ms, no operation failed); the resource capture recorded each server JVM, each container and each store's counters. Nothing else: at 50/s every setup is idle most of the time, and run-to-run variance was not measured.

## Raw results

Each setup's `result.json` (load, results, latency histogram buckets, per-second percentiles, resources, environment) and `summary.md`: [`v10-vs-9x-smoke/`](v10-vs-9x-smoke/).

## Reproducing it

From the repository root, with Docker and `JAVA_HOME` set to a JDK 21:

```bash
wow-benchmarks/e2e/harness/bench.sh build
wow-benchmarks/e2e/harness/bench.sh run --version=9x  --backend=mongo    --scenario=E1 --rate=50 --warm-up=10 --measure=20 --accounts=1000 --label=smoke
wow-benchmarks/e2e/harness/bench.sh run --version=v10 --backend=mongo    --scenario=E1 --rate=50 --warm-up=10 --measure=20 --accounts=1000 --label=smoke
wow-benchmarks/e2e/harness/bench.sh run --version=v10 --backend=postgres --scenario=E1 --rate=50 --warm-up=10 --measure=20 --accounts=1000 --label=smoke
```

# Benchmark reports

Every report names the commit, command, environment and raw results that reproduce it. How the benchmark is run and read is in [the benchmark README](../../README.md).

## Wow 10

Wow 10 is measured against itself: each change by alternating rounds against the code before it ([v10-local-baseline](v10-local-baseline.md) and the reports of the changes below), and the head against a V10 baseline by each run of the benchmark workflow (`bench.yml`), whose report is the run's own. The comparison with 9.x ended once it had settled Wow 10's advantage: E1 saturated at 3.66 times and E3 at 2.78 times 9.x's on one runner, at 14–18% less CPU per operation (Actions run 40041875440, `main` at `d73709d85`). Its last report, [v10-vs-9x](https://github.com/Ahoo-Wang/Wow/blob/26f1fa05030db5bcaeae70c34b6531dd1938c821/test/benchmarks/results/reports/v10-vs-9x.md) (roadmap B.R, 9.2), stays at that commit.

Earlier Wow 10 evidence, each valid for the commit it names:

| Report | What it answers | Status |
| --- | --- | --- |
| [v10-h1-postgres-partition-sequence](v10-h1-postgres-partition-sequence.md), [v10-h1-mongo-partition-sequence](v10-h1-mongo-partition-sequence.md) | Spike H1: partition sequences under a row lock and in MongoDB transactions | Spike, decided (D10, D11, D28) |
| [v10-h2-routing](v10-h2-routing.md) | Spike H2: whether affinity routing pays for its forwarding hop | Spike, decided (D6) |
| [v10-h3-kernel-comparison](v10-h3-kernel-comparison.md) | Spike H3: the coroutine kernel against the 9.3 Reactor kernel | Spike, decided |
| [v10-h4-ksp-coverage](v10-h4-ksp-coverage.md) | Spike H4: KSP binds the annotation model | Spike, decided |
| [v10-h6-leases](v10-h6-leases.md) | Spike H6: storage leases for membership and rebalancing | Spike, decided |
| [v10-mongo-write-amplification](v10-mongo-write-amplification.md) | B.D: store operations per command on MongoDB | Diagnostic; the paths changed since (D248, D261) |
| [v10-counts-e1-e3](v10-counts-e1-e3.md) | Store round trips, operations and allocations per operation, 9.x against Wow 10 | Superseded by [v10-vs-9x](https://github.com/Ahoo-Wang/Wow/blob/26f1fa05030db5bcaeae70c34b6531dd1938c821/test/benchmarks/results/reports/v10-vs-9x.md#store-round-trips-and-allocation-per-operation-l1); measured before R3-A, R3-B, P.16, D248 and D261 |
| [v10-ps2-partition-idle](v10-ps2-partition-idle.md) | PS-2: what an idle partition costs | Valid for its commit |
| [v10-d271-mongo-sequences-spike](v10-d271-mongo-sequences-spike.md) | Spike D271: appends on MongoDB without a transaction, owner-assigned sequences (happy path) | Spike, decided (D271) |
| [v10-d271-mongo-sequences](v10-d271-mongo-sequences.md) | D271 against `main` on MongoDB: round trips, allocation and closed-loop saturation, with the append queue off and on | Valid for its commits |
| [v10-d248-pg-append-queue](v10-d248-pg-append-queue.md) | D248 ④: the append queue on PostgreSQL, off against on, closed-loop saturation | Decided (D248 ④) |
| [v10-local-baseline](v10-local-baseline.md) | The local baseline: A/A noise, cost per operation by resource, the node's CPU by thread and allocation by part, the stores' time by statement, ranked candidates | Baseline for its commit |
| [v10-hot-aggregate-batching](v10-hot-aggregate-batching.md) | D273: an aggregate's queued commands committed with one append, `max-batch` 1 against 32: saturation with a hot account and with Zipf keys, the cost per command where nothing queues | Decided (D273) |
| [v10-local-baseline-m4pro](v10-local-baseline-m4pro.md) | The local baseline on an Apple M4 Pro (Docker 12 CPUs / 16 GB, no JFR): A/A spread, ceilings and costs per operation, 1 GB against 2 GB of heap (the state cache's budget), L1 | Baseline for its commit |
| [v10-m4pro-snapshot](v10-m4pro-snapshot.md) | The milestone snapshot on the M4 Pro after D273–D275 and the production-like store settings: ceilings, A/A, 1 GB against 2 GB, L1, and the state cache missing after four sweeps (roadmap §6 item 20) | Snapshot for its commit |
| [v10-mongo-sharding](v10-mongo-sharding.md) | MongoDB on a replica set of three and a sharded cluster (D277): the unique-key constraint, the layouts' cost, the failover tests and an end-to-end ABAB against `main` | Decision for D277 |
| [v10-pg-cross-partition](v10-pg-cross-partition.md) | PostgreSQL commits several partitions' groups with one statement (D280): L1 merging off against on, and end to end against `main` on disk (fixed rates and closed-loop sweeps), with the first version's regression | Decision for D280 |

## 9.x

[`9.x/`](9.x/) holds the reports of the 9.x JMH benchmarks (`:wow-benchmarks`), with the accepted 9.x baseline `framework-e2e-baseline.json`. They measure the 9.x command pipeline and stores, not Wow 10, and the Gradle tasks that wrote them left with the 9.x code (decision D262): they cannot be regenerated on `main`, and their paths (`wow-benchmarks/...`) are those of the 9.x tree, which is on the `v9` branch. They are kept as history (decision D265 ⑤).

---
title: Performance
description: What one Wow node does with a command, how fast on stated hardware, how it compares with Axon Framework 4 under one set of rules, and where it stops; every figure generated from a committed benchmark report.
outline: deep
---

# Performance

What one Wow node does with a command, how fast it does it on stated hardware, how it compares with Axon Framework 4 under one set of rules, and where it stops. Every number on this page is written from a committed benchmark report when the documentation is built, and each names the build, the CPU and the JDK it was measured on.

::: tip In one screen
- **On a 4-core cloud runner**, with the database, Kafka and the load generator on the same 4 cores, one node sustains writes at <!-- perf:actions-snapshot/sustainable.postgres.E1:value -->1,957.8/s<!-- /perf --> on PostgreSQL within a p99 of <!-- perf:actions-snapshot/sustainable.postgres.E1:p99Ms -->139.3 ms<!-- /perf --> (runner <!-- perf:actions-snapshot/sustainable.postgres.E1:cpu -->EPYC 7763<!-- /perf -->), and <!-- perf:actions-snapshot/sustainable.mongo.E1:value -->2,975.1/s<!-- /perf --> on MongoDB (runner <!-- perf:actions-snapshot/sustainable.mongo.E1:cpu -->Xeon 6975P-C<!-- /perf -->); each store ran on a runner of its own, so the two are not compared ([On a 4-core cloud runner](#on-a-4-core-cloud-runner)).
- **On an 18-core workstation**, one node sustains writes at <!-- perf:m5max-snapshot/sweep.postgres.1.e1-sweep:sustainable.value -->41,706.1/s<!-- /perf --> on PostgreSQL within a p99 of <!-- perf:m5max-snapshot/sweep.postgres.1.e1-sweep:sustainable.p99Ms -->167.9 ms<!-- /perf -->, at <!-- perf:m5max-snapshot/sweep.postgres.1.e1-sweep:sustainable.clients -->4,096<!-- /perf --> clients ([How far one node goes](#how-far-one-node-goes-on-a-workstation)).
- **Against Axon Framework 4** on one PostgreSQL, under the same rules, Wow's peak on the saga chain is <!-- perf:actions-axon/axon.axon-pg.100k.e3.peak:lo -->1.9<!-- /perf -->–<!-- perf:actions-axon/axon.axon-pg.100k.e3.peak:hi -->2.3<!-- /perf --> times Axon's on 2 server CPUs, and <!-- perf:m5max-axon/axon.m5max-axon-peak-e3.e3.peak:lo -->5.2<!-- /perf -->–<!-- perf:m5max-axon/axon.m5max-axon-peak-e3.e3.peak:hi -->5.4<!-- /perf --> times on 18 cores ([Against Axon Framework 4](#against-axon-framework-4)).

Sizing a deployment instead? See [Capacity planning](../operate/performance).
:::

## What Wow does with a command, and why that is cheap

A command goes to the node that owns its aggregate's partition. That node already holds the aggregate's state in its cache, so the command is decided against memory and reads nothing from the database on its way.

- **One aggregate's commands run in order in its own lane, with no lock.** Commands queued behind one commit with it, and the appends of several partitions merge into one statement, so a write takes at most one round trip to the database and one commit, and commands share both as the rate rises.
- **Off the command's path:** snapshots, sagas and sinks follow the log after the commit.
- **The answer comes when the caller asks for it** (`Wow-Wait`): `committed` for a write, `snapshot` to read your write, `queryable` when a list must show it, or a saga's next step.

**What it costs in return.** Every command keeps its request id under a unique key, so a command sent twice is handled once. That is why an event takes <!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.wow:bytesPerEvent.0 -->615<!-- /perf --> bytes in Wow's PostgreSQL log, against <!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.axon:bytesPerEvent.0 -->462<!-- /perf --> in Axon's, indexes included. A node on the workstation is ready <!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.wow:readyMs.0 -->1,790 ms<!-- /perf --> after launch, at <!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.wow:rssMbAtReady.0 -->461<!-- /perf --> MB resident (build <!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.wow:commit -->`cc11b16aa8`<!-- /perf -->, <!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.wow:cpu -->Apple M5 Max<!-- /perf -->, JDK <!-- perf:m5max-axon/footprint.m5max-axon-peak-e1.wow:jdk -->25.0.5+8<!-- /perf -->).

## How it was measured {#what-was-measured}

The domain is the benchmark's own: accounts, and a transfer between two of them run by a saga ([`test/benchmarks/server`](https://github.com/Ahoo-Wang/Wow/tree/main/test/benchmarks/server)).

| Scenario | What one operation is | Answered when |
| --- | --- | --- |
| E1 write | A deposit to one account | Its commit is stored |
| E2 read your write | A deposit, then a read of the account | The read shows the deposit |
| E3 saga chain | A transfer prepared on one account | The saga's entry on the other account is stored |
| E4 read | One account by id, or a page of ten | The answer |

- **Peak:** the best closed-loop step with at most 1% of its commands failed. **≥** marks a peak where the measurement itself could go no further.
- **Sustainable:** the highest rate whose p99 stays within 200 ms: with no error on the workstation, with at most 1% errors on the cloud runner, whose rate is found by bisection in open loop. This is the figure to plan on.
- **Latencies are of the answered commands.** Past the peak a refused command returns at once, so the answered rate is what counts.
- **Each figure names its build, CPU and JDK, and nothing is computed across builds.** A comparison is made only within one batch on one machine, its arms alternating (W A A W against Axon), so that both arms meet the same machine at the same time. Two figures from two runs are never set side by side as a difference.
- The method in full and the raw data are in the [benchmark reports](https://github.com/Ahoo-Wang/Wow/tree/main/test/benchmarks/results/reports).

## On a 4-core cloud runner

One node on a GitHub Actions runner (4 cores, 15–16 GB), with the database, Kafka and the load generator beside it, 100,000 accounts. Each suite runs on its own runner, whose CPU model its row names.

<!-- performance:begin actions -->
<!-- Generated by .github/scripts/docs-performance.mjs from the reports' headline.json; regenerate with `pnpm docs:reference`. Do not edit. -->

Each figure is from the build, the JDK and the runner its row names. The scenarios are those of [What was measured](#what-was-measured).

**GitHub Actions, one 4-core runner a suite**: [actions-snapshot](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/results/reports/actions-snapshot.md), build `e3a4e937aa`.

These rows ran on 2 CPU models, which each row names: two rows on different models do not compare the stores, nor the scenarios.

| Store | Nodes | Scenario | Keys | Peak | Sustainable | CPU, JDK, build |
| --- | ---: | --- | --- | --- | --- | --- |
| MongoDB | one | E1 | uniform | ≥ 5,010.8/s, closed loop | 2,975.1/s, open loop, p99 198.7 ms | Xeon 6975P-C, JDK 25.0.5+8, `e3a4e937aa` |
| MongoDB | one | E2 | uniform | 2,261.6/s, closed loop | 1,272.2/s, open loop, p99 190.5 ms | Xeon 6975P-C, JDK 25.0.5+8, `e3a4e937aa` |
| MongoDB | one | E3 | uniform | 1,466.6/s, closed loop | 1,008.3/s, open loop, p99 173.1 ms | Xeon 6975P-C, JDK 25.0.5+8, `e3a4e937aa` |
| PostgreSQL | one | E1 | uniform | 4,474.9/s, closed loop | 1,957.8/s, open loop, p99 139.3 ms | EPYC 7763, JDK 25.0.5+8, `e3a4e937aa` |
| PostgreSQL | one | E3 | uniform | 1,493.1/s, closed loop | 839.9/s, open loop, p99 190.5 ms | EPYC 7763, JDK 25.0.5+8, `e3a4e937aa` |
<!-- performance:end -->

Not measured on the runner: the sustainable rates of E2 on PostgreSQL and of E4.

## At light load

Most of the time a service runs far below its peak. There, a write's latency is mostly the database's sync to disk, not the node's code.

- **A saga chain on the cloud runner, PostgreSQL**: transfers at <!-- perf:actions-snapshot/cliff.postgres.E3.50:value -->50/s<!-- /perf -->, p50 <!-- perf:actions-snapshot/cliff.postgres.E3.50:p50Ms -->2.7 ms<!-- /perf --> and p99 <!-- perf:actions-snapshot/cliff.postgres.E3.50:p99Ms -->7.6 ms<!-- /perf -->; at <!-- perf:actions-snapshot/cliff.postgres.E3.500:value -->500/s<!-- /perf -->, p50 <!-- perf:actions-snapshot/cliff.postgres.E3.500:p50Ms -->1.7 ms<!-- /perf --> and p99 <!-- perf:actions-snapshot/cliff.postgres.E3.500:p99Ms -->14.2 ms<!-- /perf --> (<!-- perf:actions-snapshot/cliff.postgres.E3.500:cpu -->Xeon 6975P-C<!-- /perf -->, build <!-- perf:actions-snapshot/cliff.postgres.E3.500:commit -->`e3a4e937aa`<!-- /perf -->).
- **The same on MongoDB**: at <!-- perf:actions-snapshot/cliff.mongo.E3.50:value -->50/s<!-- /perf -->, p50 <!-- perf:actions-snapshot/cliff.mongo.E3.50:p50Ms -->5 ms<!-- /perf --> and p99 <!-- perf:actions-snapshot/cliff.mongo.E3.50:p99Ms -->14.8 ms<!-- /perf -->; at <!-- perf:actions-snapshot/cliff.mongo.E3.500:value -->500/s<!-- /perf -->, p50 <!-- perf:actions-snapshot/cliff.mongo.E3.500:p50Ms -->8.4 ms<!-- /perf --> and p99 <!-- perf:actions-snapshot/cliff.mongo.E3.500:p99Ms -->62.5 ms<!-- /perf --> (<!-- perf:actions-snapshot/cliff.mongo.E3.500:cpu -->Xeon 6975P-C<!-- /perf -->).
- **What this means for a reader:** storage with fast, steady syncs is what lowers light-load latency.

## How far one node goes on a workstation

Closed-loop sweeps from 1 to 4,096 clients on an Apple M5 Max (18 cores, 64 GB), with the database, Kafka and the load generator on the same machine, 100,000 accounts. The answered rate against p99, the 200 ms line drawn; a hollow point is a step where the node refused more than 1% of its commands.

<PerfFigure name="m5max-sweep" />

<!-- performance:begin m5max -->
<!-- Generated by .github/scripts/docs-performance.mjs from the reports' headline.json; regenerate with `pnpm docs:reference`. Do not edit. -->

Each figure is from the build, the JDK and the runner its row names. The scenarios are those of [What was measured](#what-was-measured).

**Apple M5 Max, 18 cores, 64 GB, one host**: [m5max-snapshot](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/results/reports/m5max-snapshot.md), build `103f9b6746`.

| Store | Nodes | Scenario | Keys | Peak | Sustainable | CPU, JDK, build |
| --- | ---: | --- | --- | --- | --- | --- |
| MongoDB | one | E1 | one hot account | 9,948.1/s, at 256 clients | 9,948.1/s, at 256 clients, p99 78.8 ms | Apple M5 Max, JDK 25.0.5+8, `103f9b6746` |
| MongoDB | one | E1 | uniform | ≥ 28,609.4/s, at 4,096 clients | 28,196.1/s, at 1,024 clients, p99 66.6 ms | Apple M5 Max, JDK 25.0.5+8, `103f9b6746` |
| MongoDB | one | E1 | Zipf | 15,957.5/s, at 1,024 clients | 14,914.4/s, at 256 clients, p99 42.2 ms | Apple M5 Max, JDK 25.0.5+8, `103f9b6746` |
| MongoDB | one | E3 | uniform | 7,738.2/s, at 1,024 clients | 5,898.6/s, at 256 clients, p99 72.2 ms | Apple M5 Max, JDK 25.0.5+8, `103f9b6746` |
| PostgreSQL | one | E1 | one hot account | 11,853.7/s, at 256 clients | 11,853.7/s, at 256 clients, p99 76.8 ms | Apple M5 Max, JDK 25.0.5+8, `103f9b6746` |
| PostgreSQL | one | E1 | uniform | ≥ 41,706.1/s, at 4,096 clients | 41,706.1/s, at 4,096 clients, p99 167.9 ms | Apple M5 Max, JDK 25.0.5+8, `103f9b6746` |
| PostgreSQL | one | E1 | Zipf | 19,746.8/s, at 256 clients | 19,746.8/s, at 256 clients, p99 29.6 ms | Apple M5 Max, JDK 25.0.5+8, `103f9b6746` |
| PostgreSQL | one | E3 | uniform | 8,042.7/s, at 1,024 clients | 3,637.9/s, at 64 clients, p99 185.3 ms | Apple M5 Max, JDK 25.0.5+8, `103f9b6746` |

Three nodes on this one host: the nodes, the stores and the load generator divide the same cores, so these rows show what a cluster costs (forwarding between the nodes), not what it gains. Scaling across separate machines has not been measured.

| Store | Nodes | Scenario | Keys | Peak | Sustainable | CPU, JDK, build |
| --- | ---: | --- | --- | --- | --- | --- |
| MongoDB | 3 | E1 | uniform | 19,052.3/s, at 1,024 clients | 19,052.3/s, at 1,024 clients, p99 131.1 ms | Apple M5 Max, JDK 25.0.5+8, `103f9b6746` |
| MongoDB | 3 | E3 | uniform | 6,060.2/s, at 1,024 clients | 5,462.9/s, at 256 clients, p99 78.8 ms | Apple M5 Max, JDK 25.0.5+8, `103f9b6746` |
| PostgreSQL | 3 | E1 | uniform | 25,104.3/s, at 1,024 clients | 25,104.3/s, at 1,024 clients, p99 90.6 ms | Apple M5 Max, JDK 25.0.5+8, `103f9b6746` |
| PostgreSQL | 3 | E3 | uniform | 8,623/s, at 1,024 clients | 7,279/s, at 256 clients, p99 59.6 ms | Apple M5 Max, JDK 25.0.5+8, `103f9b6746` |
<!-- performance:end -->

## A hot aggregate

One aggregate's commands run one after another on its owner, so one hot account bounds what any single aggregate takes, whatever the cluster's size. On the workstation one hot account peaks at <!-- perf:m5max-snapshot/sweep.postgres.1.e1-hot-sweep:value -->11,853.7/s<!-- /perf --> on PostgreSQL and <!-- perf:m5max-snapshot/sweep.mongo.1.e1-hot-sweep:value -->9,948.1/s<!-- /perf --> on MongoDB; keys drawn from a Zipf distribution peak at <!-- perf:m5max-snapshot/sweep.postgres.1.e1-zipf-sweep:value -->19,746.8/s<!-- /perf --> and <!-- perf:m5max-snapshot/sweep.mongo.1.e1-zipf-sweep:value -->15,957.5/s<!-- /perf -->. Spread load over aggregates where the model allows.

## Past the peak

A node refuses what it cannot take, with `429 wow.command.busy` and `Retry-After`. It does not queue without end: past the peak it goes on answering while it refuses the rest at once, and the hollow points of the figure above show the rate it still answered at each such step.

The limit that remains: a saga's command waits on a subscription, and admission does not see a subscription's queue. When a downstream subscription lags, overload can still show as a `504` after 10 s rather than a `429`.

## A cold cache

With a million accounts and nothing cached, on the cloud runner (build <!-- perf:actions-snapshot/cold.postgres.E1:commit -->`e3a4e937aa`<!-- /perf -->; runners <!-- perf:actions-snapshot/cold.postgres.E1:cpu -->Xeon 6975P-C<!-- /perf --> for PostgreSQL and <!-- perf:actions-snapshot/cold.mongo.E1:cpu -->EPYC 9V74<!-- /perf --> for MongoDB):

- a write at <!-- perf:actions-snapshot/cold.postgres.E1:value -->200/s<!-- /perf --> answers within a p99 of <!-- perf:actions-snapshot/cold.postgres.E1:p99Ms -->5.1 ms<!-- /perf --> on PostgreSQL and <!-- perf:actions-snapshot/cold.mongo.E1:p99Ms -->154.6 ms<!-- /perf --> on MongoDB;
- a point read at <!-- perf:actions-snapshot/cold.postgres.E4-state:value -->500/s<!-- /perf --> within <!-- perf:actions-snapshot/cold.postgres.E4-state:p99Ms -->3 ms<!-- /perf --> and <!-- perf:actions-snapshot/cold.mongo.E4-state:p99Ms -->4.9 ms<!-- /perf -->.

## Three nodes, and a node lost

Three nodes on one cloud runner (build <!-- perf:actions-snapshot/cluster.postgres.e1-steady:commit -->`e3a4e937aa`<!-- /perf -->; runners <!-- perf:actions-snapshot/cluster.postgres.e1-steady:cpu -->EPYC 7763<!-- /perf --> for PostgreSQL and <!-- perf:actions-snapshot/cluster.mongo.e1-steady:cpu -->Xeon 6975P-C<!-- /perf --> for MongoDB):

- **In steady state**, writes at <!-- perf:actions-snapshot/cluster.postgres.e1-steady:value -->150/s<!-- /perf --> answer within a p99 of <!-- perf:actions-snapshot/cluster.postgres.e1-steady:p99Ms -->14.1 ms<!-- /perf --> on PostgreSQL and <!-- perf:actions-snapshot/cluster.mongo.e1-steady:p99Ms -->13.7 ms<!-- /perf --> on MongoDB.
- **A node stopped mid-run:** its partitions move to the others in seconds. The worst second's p99 of writes was <!-- perf:actions-snapshot/cluster.postgres.e1-handover:worstSecondP99Ms -->3,129.3 ms<!-- /perf --> on PostgreSQL and <!-- perf:actions-snapshot/cluster.mongo.e1-handover:worstSecondP99Ms -->1,671.2 ms<!-- /perf --> on MongoDB; <!-- perf:actions-snapshot/cluster.postgres.e1-handover:failed -->0<!-- /perf --> and <!-- perf:actions-snapshot/cluster.mongo.e1-handover:failed -->0<!-- /perf --> writes failed. Through the same hand-over, <!-- perf:actions-snapshot/cluster.mongo.e3-handover:failed -->39<!-- /perf --> saga chains failed on MongoDB and <!-- perf:actions-snapshot/cluster.postgres.e3-handover:failed -->1<!-- /perf --> on PostgreSQL, one run each. MongoDB's come after the stopped node's restart, while its partitions caught up a few at a time: that is [#6407](https://github.com/Ahoo-Wang/Wow/issues/6407), fixed after this build by [#6412](https://github.com/Ahoo-Wang/Wow/pull/6412) ([the report](https://github.com/Ahoo-Wang/Wow/blob/main/test/benchmarks/results/reports/actions-snapshot.md#l4-cluster-and-hand-over-e5) has the timing).
- **Not measured: scaling across separate machines.** These nodes share one machine with the database, so they show what a cluster costs, not what it gains. [Capacity planning](../operate/performance) gives the bound, and the run to make on your own machines.

## Against Axon Framework 4

**What "fair" means here:**

- **The same setup on both sides:** the same domain, load generator, harness and PostgreSQL, and the same connection pool (Spring Boot's 10), with a run at 32 apart.
- **Axon 4 as its reference guide configures production without Axon Server:** a JDBC event store and token store, a tracking processor of 16 segments, a cache, and a snapshot every 100 events.
- **Wow with publishing off,** since Axon publishes nothing; the same 10 s timeout on both.
- **Noise:** the arms alternate in one batch, W A A W, and a difference within an arm's own A/A spread is called level.
- **Correctness:** each session's database is checked afterwards; an arm whose data is wrong is not compared.

On 2 server CPUs (a GitHub Actions runner), each range Wow's rate divided by Axon's inside the band of the arms' own spread; a hollow range is level:

<PerfFigure name="actions-axon" />

On 18 cores (the workstation), in a batch of its own at its own build:

<PerfFigure name="m5max-axon" />

Not compared: a tuned Axon (a Disruptor command bus, batching), Axon 5, Axon Server, and Wow with Kafka.

## Run it yourself

On your own machine, with Docker and JDK 25:

```bash
test/benchmarks/harness/bench.sh build
test/benchmarks/harness/bench.sh up --backend=postgres --accounts=100000 --prime
test/benchmarks/harness/bench.sh saturate --reuse --backend=postgres --accounts=100000 --scenario=E1 --sweep
test/benchmarks/harness/bench.sh down
```

On GitHub's runners: `gh workflow run bench.yml -f tier=L4 -f head_ref=<tag>`. The [benchmark README](https://github.com/Ahoo-Wang/Wow/tree/main/test/benchmarks) has every option.

## Where the limits are

| Limit | What to do |
| --- | --- |
| One database carries every node | A faster or less shared database; MongoDB sharding; aggregate types on separate stores |
| A hot aggregate ([above](#a-hot-aggregate)) | Model so that the load spreads over aggregates |
| Light-load latency is the disk's sync | Storage with fast, steady syncs |
| One aggregate type's log on one database | Planned for 10.x |
| More bytes per event than Axon | The price of handling a command sent twice once |
| Admission does not see subscription queues | Plan on the sustainable rate, not the peak |

And the measurement's own limits: the database, the load generator and the node share a machine; the workstation's figures are single runs; no cluster was measured on separate machines; and a cloud runner's figures move with its CPU model, which each row names.

# Wow 10 on GitHub Actions at 10.0.0-beta.6: L3 and L4

The L3 and L4 runs at `v10.0.0-beta.6` (`e3a4e937aa`), on GitHub Actions on 2026-11-17. This report quotes beta.6's
own figures:

- the unit costs of each L3 suite, at a rate calibrated from beta.6's own saturation;
- the rate each scenario sustains with p99 within 200 ms (L4);
- E3 in fixed steps up to 500/s (L4);
- a three-node cluster, steady and with a node's hand-over (L4);
- a cold cache over a million accounts (L4).

L3 also ran `v10.0.0-beta.5` (`103f9b6746`) as its base arm, alternating with beta.6 on each runner. Those pairs and
their ratios are the release notes' and are not quoted here. Every figure below is beta.6's, on JDK 25.0.5+8, and
names its runner's CPU. Each suite ran on a runner of its own, and the CPU model changes between them, so no two rows
are compared.

## Summary

- **Sustainable rates (≤ 1% errors, p99 ≤ 200 ms), one node on a 4-core runner, 100k accounts:**
  - MongoDB, on a Xeon 6975P-C: E1 2,975/s, E2 1,272/s, E3 1,008/s.
  - PostgreSQL, on an EPYC 7763: E1 1,958/s, E3 840/s.
- **No cliff up to 500/s.** E3 kept up at every step on both stores (Xeon 6975P-C each), with no failure and no
  command refused.
- **Three nodes, steady:** the full rate on both stores with no failure, p99 13.7–27.0 ms.
- **Three nodes, a node stopped and restarted:**
  - E1 carried the full rate on both stores with no failure. The worst second's p99 is 1.7 s on MongoDB and 3.1 s on
    PostgreSQL.
  - E3 on MongoDB failed 39 of 4,500, all `504 wow.wait.timeout` on committed commands. Its p99 is 5.7 s, its worst
    second's 9.9 s, and its lowest second 40/s.
  - E3 on PostgreSQL failed 1 (`503 wow.node.closing`), with p99 668 ms.
  - Each is one run. No cause is stated.
- **Cold cache, a million accounts:**
  - On PostgreSQL (Xeon 6975P-C), E1 at 200/s has p99 5.1 ms and E4 point reads at 500/s 3.0 ms.
  - On MongoDB (EPYC 9V74), E1 has p50 3.6 ms and p99 154.6 ms, beyond 10× its p50. E4 has p99 4.9 ms.
- **L3 at 1k accounts on MongoDB:** E1, E3 and the mixed load fail commands by `504 wow.wait.timeout` at the rate
  calibrated from their saturation. These suites put the whole load on 1,000 aggregates. The 100k suites failed none,
  apart from 159 of 181,860 in the mixed load on PostgreSQL.

## Setup

| | |
| --- | --- |
| Runs | L3 [44123394372](https://github.com/Ahoo-Wang/Wow/actions/runs/44123394372) (`tier=L3`) and L4 [44131893810](https://github.com/Ahoo-Wang/Wow/actions/runs/44131893810) (`tier=L4`), dispatched from `main` |
| Build | `v10.0.0-beta.6`, `e3a4e937aa`, in both runs |
| L3 base arm | `v10.0.0-beta.5`, `103f9b6746`. Not quoted here |
| Harness | Built from `e3a4e937aa` for both arms |
| Runners | GitHub-hosted, 4 cores and 15–16 GB each, JDK 25.0.5+8 (Temurin) on every job. L3: AMD EPYC 9V74 (7 suites), 9V45 (4), 7763 (4), Intel Xeon Platinum 8573C (4), 8370C (1). L4: Intel Xeon 6975P-C (5), AMD EPYC 7763 (2), 9V74 (1). Each row below names its own |
| Topology | One node unless named. The server, the load generator, the store and Kafka share the runner. Heap 1 GB, G1 |
| Data | L3 at 1k accounts (every cache holds them) and 100k (primed). L4 at 100k (primed), cold at 1,000,000 (not primed) |
| Method | Warm-up 30 s and measurement 40 s (E5: 90 s). L3: each arm's closed-loop saturation, then three rounds per arm at a share of it, alternating with the base arm on one runner. L4: sustainable rates by bisection, fixed E3 steps of a minute each, E5 steady and hand-over |

## L3: beta.6's rounds

Each suite's closed-loop saturation (with the concurrency of its peak), then the median of beta.6's three rounds at the
rate calibrated from it. CPU per operation is the node's, the store's and Kafka's together. Failed counts all three
rounds.

| Suite | Runner | Saturation (clients) | Rate | Throughput | p50 | p99 | CPU ms/op | Alloc/op | Failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| E1 MongoDB, uniform, 100k | EPYC 9V74 | ≥ 2,952/s (2048) | 1,771.1/s | 1,771.1/s | 75.3 ms | 610.3 ms | 1.09 | 73 KB | 0 of 212,532 |
| E1 MongoDB, Zipf, 100k | EPYC 9V45 | 3,721/s (512) | 2,232.6/s | 2,232.6/s | 24.1 ms | 274.4 ms | 0.93 | 76 KB | 0 of 267,912 |
| E1 PostgreSQL, 100k | Xeon 8573C | 3,306/s (1024) | 1,487.5/s | 1,487.5/s | 10.2 ms | 170.0 ms | 1.34 | 77 KB | 0 of 178,500 |
| E2 MongoDB, 100k | EPYC 9V74 | ≥ 1,450/s (2048) | 869.8/s | 869.8/s | 121.9 ms | 432.1 ms | 2.21 | 152 KB | 0 of 104,376 |
| E3 MongoDB, 100k | EPYC 7763 | 625/s (1024) | 375.2/s | 375.2/s | 51.7 ms | 245.8 ms | 5.35 | 224 KB | 0 of 45,024 |
| E3 PostgreSQL, 100k | EPYC 7763 | 798/s (64) | 478.7/s | 478.7/s | 20.7 ms | 187.4 ms | 4.20 | 208 KB | 0 of 57,444 |
| Mixed MongoDB, 100k | EPYC 9V45 | 3,072/s (256) | 1,843.3/s | 1,843.3/s | 9.1 ms | 118.8 ms | 1.13 | 85 KB | 0 of 221,196 |
| Mixed PostgreSQL, 100k | Xeon 8370C | 2,526/s (64) | 1,515.5/s | 1,514.8/s | 6.5 ms | 128.5 ms | 1.28 | 63 KB | 159 of 181,860 |
| Large MongoDB, 100k | EPYC 9V45 | 1,747/s (64) | 1,048.4/s | 1,048.4/s | 23.7 ms | 942.1 ms | 1.65 | 161 KB | 0 of 125,808 |
| Large PostgreSQL, 100k | EPYC 9V74 | 1,214/s (128) | 546.4/s | 546.4/s | 6.3 ms | 176.1 ms | 3.19 | 173 KB | 0 of 65,568 |
| E1 MongoDB, uniform, 1k | Xeon 8573C | ≥ 2,885/s (2048) | 973.8/s | 933.1/s | 1,081.3 ms | 5,668.9 ms | 2.27 | 77 KB | 5,999 of 116,856 |
| E1 MongoDB, Zipf, 1k | EPYC 9V74 | 2,088/s (1024) | 704.6/s | 703.9/s | 128.0 ms | 5,406.7 ms | 3.16 | 90 KB | 63 of 84,552 |
| E1 PostgreSQL, 1k | EPYC 9V45 | 4,117/s (512) | 1,389.4/s | 1,389.4/s | 12.5 ms | 327.7 ms | 1.44 | 81 KB | 0 of 166,728 |
| E2 MongoDB, 1k | EPYC 9V74 | ≥ 1,369/s (2048) | 462.0/s | 462.0/s | 77.8 ms | 684.0 ms | 4.67 | 180 KB | 0 of 55,437 |
| E3 MongoDB, 1k | EPYC 9V74 | 935/s (512) | 315.5/s | 260.1/s | 4,849.7 ms | 10,092.5 ms | 8.15 | 351 KB | 5,269 of 37,860 |
| E3 PostgreSQL, 1k | Xeon 8573C | 748/s (512) | 252.3/s | 252.3/s | 155.6 ms | 4,112.4 ms | 7.90 | 259 KB | 7 of 30,276 |
| Mixed MongoDB, 1k | Xeon 8573C | 2,344/s (512) | 791.0/s | 779.8/s | 35.8 ms | 1,015.8 ms | 2.82 | 94 KB | 1,069 of 94,920 |
| Mixed PostgreSQL, 1k | EPYC 7763 | 1,369/s (256) | 462.0/s | 462.0/s | 7.3 ms | 242.7 ms | 4.34 | 76 KB | 0 of 55,437 |
| Large MongoDB, 1k | EPYC 7763 | 1,551/s (1024) | 523.3/s | 523.3/s | 34.3 ms | 354.3 ms | 4.42 | 174 KB | 0 of 62,796 |
| Large PostgreSQL, 1k | EPYC 9V74 | 1,422/s (128) | 479.9/s | 479.9/s | 17.7 ms | 569.3 ms | 3.87 | 178 KB | 0 of 57,588 |

- **A rate here is a share of a closed-loop peak, not a sustainable rate.** p99 above 200 ms at that rate does not
  mean the scenario cannot hold 200 ms. L4's bisection answers that, below.
- **At 1k accounts the load is on 1,000 aggregates.** E1 and E3 on MongoDB fail by `504 wow.wait.timeout`: the caller's
  wait for the command ran out after 10 s. Part of E1's failures (uniform) are the load generator's: its
  2,048 requests in flight were full.
- **A saturation marked `≥`** reached the search's 2,048 clients still gaining, so the peak is at least that.

## L4: sustainable rates

The highest open-loop rate with at most 1% errors and p99 within 200 ms, found by bisection. One node, 100k accounts,
primed.

| Store | Runner | Scenario | Sustainable | Bracket | p50 | p99 | CPU ms/op | Saturation (clients) | Share |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MongoDB | Xeon 6975P-C | E1 | **2,975/s** | 2,975–3,132/s | 18.2 ms | 198.7 ms | 0.95 | ≥ 5,011/s (2048) | 59% |
| MongoDB | Xeon 6975P-C | E2 | **1,272/s** | 1,272–1,343/s | 28.8 ms | 190.5 ms | 2.20 | 2,262/s (512) | 56% |
| MongoDB | Xeon 6975P-C | E3 | **1,008/s** | 1,008–1,054/s | 31.4 ms | 173.1 ms | 2.80 | 1,467/s (256) | 69% |
| PostgreSQL | EPYC 7763 | E1 | **1,958/s** | 1,958–2,098/s | 11.1 ms | 139.3 ms | 1.33 | 4,475/s (1024) | 44% |
| PostgreSQL | EPYC 7763 | E3 | **840/s** | 840–887/s | 21.8 ms | 190.5 ms | 3.34 | 1,493/s (128) | 56% |

The two stores ran on different CPU models, so these rows do not compare MongoDB with PostgreSQL. E2 was not measured
on PostgreSQL, and E4 not at all, in this suite.

## L4: cliff

E3 in fixed open-loop steps, a minute each on fresh containers, both stores on a Xeon 6975P-C.

| Rate | PostgreSQL p50 / p99 | PostgreSQL worst second | MongoDB p50 / p99 | MongoDB worst second |
| --- | --- | --- | --- | --- |
| 50/s | 2.7 / 7.6 ms | 31.5 ms | 5.0 / 14.8 ms | 36.1 ms |
| 100/s | 2.1 / 6.7 ms | 36.6 ms | 4.1 / 19.8 ms | 62.5 ms |
| 200/s | 1.8 / 6.4 ms | 52.2 ms | 4.0 / 34.0 ms | 122.9 ms |
| 300/s | 1.7 / 9.2 ms | 52.2 ms | 5.1 / 38.1 ms | 91.6 ms |
| 400/s | 1.6 / 11.1 ms | 48.4 ms | 7.2 / 40.7 ms | 66.6 ms |
| 500/s | 1.7 / 14.2 ms | 58.4 ms | 8.4 / 62.5 ms | 174.1 ms |

Every step kept up on both stores, with no failure and no command refused as busy. One tail fell back to the store
once, on MongoDB at 200/s (`tailRead idle`).

## L4: cluster and hand-over (E5)

Three nodes, requests round-robin, 90 s measured, 100k accounts. In the hand-over runs node 2 is stopped 25 s into the
measured phase and restarted 20 s later. MongoDB ran on a Xeon 6975P-C, PostgreSQL on an EPYC 7763.

| Store | Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Lowest second |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | E1 steady | 150/s | 150/s | 0 | 3.3 ms | 14.1 ms | 36.4 ms | 150/s |
| PostgreSQL | E1 hand-over | 150/s | 150/s | 0 | 4.8 ms | 1,044.5 ms | 3,129.3 ms | 150/s |
| PostgreSQL | E3 steady | 50/s | 50/s | 0 | 6.7 ms | 27.0 ms | 67.1 ms | 50/s |
| PostgreSQL | E3 hand-over | 50/s | 50/s | 1 | 9.9 ms | 667.6 ms | 2,030.4 ms | 49/s |
| MongoDB | E1 steady | 150/s | 150/s | 0 | 3.0 ms | 13.7 ms | 34.3 ms | 150/s |
| MongoDB | E1 hand-over | 150/s | 150/s | 0 | 4.4 ms | 66.0 ms | 1,671.2 ms | 150/s |
| MongoDB | E3 steady | 50/s | 50/s | 0 | 7.1 ms | 26.9 ms | 59.8 ms | 50/s |
| MongoDB | E3 hand-over | 50/s | 49.6/s | 39 | 9.2 ms | 5,701.6 ms | 9,920.0 ms | 40/s |

- **The steady runs carried the full rate** with no failure and no node signal.
- **In the hand-overs,** the nodes logged `stateRebuilt ungated-ran` 70–110 times and `forwardFailed local` 10–15
  times, and their tails read the store after a gap 4–21 times.
- **MongoDB's E3 hand-over failed 39 of 4,500,** all `504 wow.wait.timeout` on commands that had committed
  (`waitTimedOut COMMITTED`): the command was written, and its caller's wait for it ran out.
- **PostgreSQL's E3 hand-over failed 1,** `503 wow.node.closing`, refused by a node as it closed.
- Each run is one run on one runner, with no A/A, and no cause is stated for either tail.

## L4: cold cache, a million accounts

Not primed on purpose: the caches start empty, and the keys are uniform over 1,000,000 accounts. That is more than the
node's state cache holds (a tenth of the 1 GB heap, about 105,000 states), so the steady state is a full cache with
evictions.

| Store | Runner | Scenario | Rate | Failed | p50 | p95 | p99 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | Xeon 6975P-C | E1 | 200/s | 0 | 1.3 ms | 3.0 ms | 5.1 ms | 54.8 ms | 4.26 | 120 KB |
| PostgreSQL | Xeon 6975P-C | E4 point read | 500/s | 0 | 0.4 ms | 1.4 ms | 3.0 ms | 10.6 ms | 1.15 | 36 KB |
| MongoDB | EPYC 9V74 | E1 | 200/s | 0 | 3.6 ms | 72.7 ms | 154.6 ms | 234.5 ms | 8.00 | 257 KB |
| MongoDB | EPYC 9V74 | E4 point read | 500/s | 0 | 0.7 ms | 2.7 ms | 4.9 ms | 30.5 ms | 2.18 | 76 KB |

MongoDB's E1 p99 is beyond 10× its p50, and MongoDB was the runner's busiest process (0.77 cores). It is one 40 s run
of 8,000 operations, and no cause is stated.

## Caveats

- **One shared 4-core runner per job.** The server, the store, Kafka and the load generator compete for the same cores,
  so absolute rates are far below a dedicated deployment's. Production sizing needs a run on the target hardware.
- **The CPU model changes between jobs.** Each row names its own. Two rows on different models are not a comparison,
  of stores or of scenarios.
- **Each L4 figure is one run.** None has an A/A, so none has a noise band.
- **The 1k suites have no noise threshold.** Their rounds are at a third of saturation, where the latency spread
  between rounds is wide.

## Data

[`actions-snapshot/`](actions-snapshot/) holds beta.6's raw results:

- `l4/report.md`, the L4 run's report, with every round, probe and anomaly;
- per suite, `suite.json` (the run's record of every round, both arms in L3), and in L4 `summary.md`;
- per round or probe of beta.6, `result.json`: latencies, per-second series, histogram, resources and environment,
  commit included;
- per closed-loop search of beta.6, `saturation.json`;
- `headline.json`, the figures the documentation quotes, written from the above by `bench-headline.mjs`.

The base arm's rounds, L3's report with its ratios, and the node, seed, prime and settle logs stay in the runs'
artifacts.

## Reproduce

```bash
gh workflow run bench.yml --ref main -f head_ref=v10.0.0-beta.6 -f base_ref=v10.0.0-beta.5 -f tier=L3
```

```bash
gh workflow run bench.yml --ref main -f head_ref=v10.0.0-beta.6 -f tier=L4
```

The workflow keeps one pending run per ref, so a second run is dispatched from another branch. Each job uploads
`bench-result-<suite>`; the Report job uploads `e2e-bench-report`.

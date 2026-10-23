# Wow 10 benchmark: against 9.x, sustainable rates, cluster and cold cache

The end-to-end benchmark of Wow 10 (decision D224, roadmap B.R and 9.2), measured on GitHub Actions on 2026-10-22. It answers five questions:

- How Wow 10 compares with 9.x.
- Whether the current code regressed against `10.0.0-alpha.4`.
- What rate each scenario sustains with p99 within 200 ms.
- How a three-node cluster behaves, including a node's hand-over.
- How a cold cache over a million accounts behaves.

It replaces the preliminary counts report `v10-counts-e1-e3` for the store round trips and allocation per operation.

## Summary

- **Against 9.x on MongoDB, Wow 10 has about twice the ceiling.**
  - E1 writes: closed-loop saturation is at least 1,414/s against 650/s.
  - E3 saga chains: 493/s against 220/s.
  - At its calibrated rate Wow 10 carries 1.22× (E1) and 1.68× (E3) the throughput, at 0.62–0.83× the CPU and 0.62–0.81× the allocation per operation.
  - At one rate for both, E3's tails are shorter on Wow 10: p95 0.55–0.84×, p99 0.70–0.96×.
  - **E1's latency at an equal rate is worse:** p50 2.1–3.9×, p99 1.2–1.5×, with MongoDB at 1.7 cores against 1.35. Every Wow 10 append is a multi-document transaction (D11).
- **No regression against `10.0.0-alpha.4`.** The commits measured differ from the tag only in documentation and comments, so this pair is in effect an A/A check of the method.
  - Throughput, CPU and allocation per operation agree within a few per cent.
  - Latencies at 45–60% of saturation differ by up to 6× between runs of the same code. On these runners they are noise, not a signal.
- **Sustainable rates (≤ 1% errors, p99 ≤ 200 ms), one node on a 4-core runner:**
  - PostgreSQL: E1 837/s, E3 458/s.
  - MongoDB: E1 276/s, E2 145/s, E3 78/s.
  - These are 37% (E1) and 50% (E3) of saturation on PostgreSQL, and 16–22% on MongoDB. Tail latency, not throughput, sets the usable capacity.
- **Cliff.**
  - E3 on PostgreSQL kept up at every step to 500/s, with p99 at most 133 ms.
  - On MongoDB it kept up to 300/s with p50 2.6 s, then collapsed at 400/s: busy refusals, wait timeouts and paused saga partitions, which resumed.
  - 9.x on MongoDB collapsed between 300/s and 400/s too, but from p50 5.6 s at 300/s.
- **Cluster.** The first L4 run found a defect: on three nodes, wait signals between the nodes timed out at a fixed 1 s while the seeding saturated the runner's CPUs, and committed creations answered `504 wow.wait.timeout`. A signal now gets its wait's deadline instead (#4822). The rerun dropped no signal and timed out no wait; both seeds of 100,000 accounts and every steady run completed without a failure.
- **Hand-over** of a stopped node completes with no second below half the rate. The worst second's p99 is 1.6–7.4 s, consistent with the lease TTL of 10 s.
- **Cold cache, a million accounts, not primed:**
  - PostgreSQL: E1 at 200/s with p99 6.4 ms; point reads at 500/s with p99 2.8 ms.
  - MongoDB: 110 ms and 6.6 ms.

## Setup

| | |
| --- | --- |
| Runs | L3 grid with the 9.x suites [39722957720](https://github.com/Ahoo-Wang/Wow/actions/runs/39722957720) (`tier=L3`, `suites=ab,component,vs-9x`); L4 [39722970362](https://github.com/Ahoo-Wang/Wow/actions/runs/39722970362) (`tier=L4`); E5 again after the signal fix, [39893318539](https://github.com/Ahoo-Wang/Wow/actions/runs/39893318539) (`suites=e5-pg,e5-mongo`, 2026-10-23) |
| Head | `main` at `d3336ce650` (L3) and `1ac5ef00ac` (L4): the code of `10.0.0-alpha.4` with documentation and comment changes only. The E5 rerun: `main` at `203ab6ecdf`, with the signal fix (#4822), the measured-phase signal counts (#4823) and the settle after the prime (#4824) |
| Baseline | `10.0.0-alpha.4` (`c57cedbc28`), the default `base_ref` |
| 9.x | branch `v10-9x-baseline`, the 9.x twin of the domain (D262) |
| Runners | GitHub-hosted, 4 cores each: AMD EPYC 7763, 9V45, 9V74, Intel Xeon 8370C, 8573C. The processor differs between jobs. |
| Topology | One node unless named. The server, load generator, the store and Kafka share the runner (`--server-cpus`, `--load-cpus`). The heap is 1 GB. |
| Data | 100,000 accounts with uniform keys (Zipf where named), primed: each touched once before the warm-up. The state cache holds 4,096 per context, so the steady state is a full cache with evictions. Cold: 1,000,000 accounts, not primed. |
| Method | Warm-up 30 s and measurement 40 s (E5: 15 s and 90 s). Each arm's closed-loop saturation is found first, then open-loop rounds run at a calibrated share of it (45–80%), pairing head and baseline on the same runner. Sustainable rates come from bisection. |

Scenarios: the completion points are in the [benchmark README](../../README.md#completion-points).
- E1: a deposit, answered once committed.
- E2: read-your-write.
- E3: a transfer saga, complete when the target's entry is stored.
- E4: point reads and lists.
- E5: a three-node cluster.
- Mixed: 50% E1, 40% point reads, 10% lists.
- Large: five events of about 2 KB per command.

As D224 rules, Wow 10 on PostgreSQL is reported on its own and never compared with 9.x.

## Wow 10 against 9.x (MongoDB)

| Scenario | Saturation 9.x | Saturation Wow 10 | Throughput at calibrated rate, Wow 10 / 9.x | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- |
| E1 | 650/s | ≥ 1,414/s | 1.22 | 0.83 | 0.79–0.81 |
| E3 | 220/s | 493/s | 1.68 | 0.61–0.62 | 0.62 |

Latencies at one rate for both arms (E1 at 520/s, E3 at 176/s), Wow 10 against 9.x:

| Scenario | p50 | p95 | p99 | Worst-second p99 |
| --- | --- | --- | --- | --- |
| E1 | 2.08–3.90 | 1.19–1.83 | 1.20–1.51 | 1.52–1.54 |
| E3 | 1.05–1.36 | 0.55–0.84 | 0.70–0.96 | 1.06–1.11 |

Absolute values at E1's equal rate:

| Version | p50 | p95 | p99 | MongoDB CPU |
| --- | --- | --- | --- | --- |
| 9.x | 39–58 ms | 311–381 ms | 514–545 ms | 1.35 cores |
| Wow 10 | 121–154 ms | 455–569 ms | 655–778 ms | 1.70 cores |

Both are bounded by MongoDB on a shared 4-core runner. The ratio, not the milliseconds, is the finding: per write Wow 10 does more work in MongoDB at a low rate, in exchange for twice the ceiling.

## Regression check against `10.0.0-alpha.4`

| Suite | Saturation base → head | CPU ms/op base → head | Alloc/op base → head |
| --- | --- | --- | --- |
| E1 PostgreSQL | 1,925 → 1,886/s | 2.21–2.25 → 2.23–2.24 | 120–124 → 120–122 KB |
| E1 MongoDB, uniform | ≥ 2,284 → ≥ 2,253/s | 2.77–2.80 → 2.79–2.82 | 239–250 → 220–245 KB |
| E1 MongoDB, Zipf | 505 → 535/s | 6.41–6.56 → 6.37–6.58 | 265–272 → 265–267 KB |
| E2 MongoDB | ≥ 818 → ≥ 731/s | 6.46–6.48 → 6.44 | 335–351 → 328–335 KB |
| E3 PostgreSQL | 1,123 → 1,328/s | 3.16–3.18 → 3.10–3.22 | 293–294 → 291–305 KB |
| E3 MongoDB | 519 → 578/s | 9.46–9.50 → 9.56 | 556–563 → 558–562 KB |
| Mixed PostgreSQL | 1,943 → 1,858/s | 2.83–2.84 → 2.88–2.95 | 87 → 87 KB |
| Mixed MongoDB | 838 → 820/s | 5.06–5.12 → 5.07–5.09 | 204–207 → 206 KB |
| Large PostgreSQL | 1,253 → 1,212/s | 3.53–3.67 → 3.56–3.62 | 270–281 → 269–272 KB |
| Large MongoDB | 1,039 → ≥ 883/s | 6.20–6.24 → 6.22 | 415–421 → 421–425 KB |

CPU and allocation per operation are compared at one rate for both arms. They agree within the A/A noise.

Saturation varies by up to ±18% between two closed-loop searches of the same code. Latencies vary more: E1 on PostgreSQL at 60% of saturation had p99 from 0.9 s to 3.1 s on both arms. Large on PostgreSQL had a baseline p50 of 13–291 ms against a head p50 of 22–29 ms, for the same code.

## Sustainable rates (L4)

The highest open-loop rate with at most 1% errors and p99 within 200 ms, found by bisection; one node.

| Backend | Scenario | Sustainable | p50 | p99 | CPU ms/op | Saturation | Share |
| --- | --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | E1 | **837/s** | 4.0 ms | 128 ms | 2.76 | 2,231/s | 37% |
| PostgreSQL | E3 | **458/s** | 7.9 ms | 189 ms | 5.60 | 916/s | 50% |
| MongoDB | E1 | **276/s** | 10.6 ms | 200 ms | 9.07 | 1,264/s | 22% |
| MongoDB | E2 | **145/s** | 10.9 ms | 122 ms | 14.73 | ≥ 774/s | ≤ 19% |
| MongoDB | E3 | **78/s** | 11.5 ms | 199 ms | 27.59 | 498/s | 16% |

How the tail grows on PostgreSQL E1:
- 558/s: p99 23 ms.
- 837/s: p99 128 ms.
- 906/s: p99 623 ms.
- 976/s: p99 852 ms.
- 1,115/s: p99 4.1 s.

Across that range the server uses about 1 core and PostgreSQL 0.5–0.9 of one. The tail grows long before either is saturated.

## Cliff (L4)

E3 in fixed open-loop steps of 40 s each.

| Rate | PostgreSQL p50 / p99 | MongoDB p50 / p99 | 9.x on MongoDB p50 / p99 |
| --- | --- | --- | --- |
| 50/s | 2.6 / 13.7 ms | 12.0 / 90 ms | 7.6 / 82 ms |
| 100/s | 2.3 / 14.9 ms | 21.8 / 254 ms | 12.8 / 360 ms |
| 200/s | 2.2 / 34.3 ms | 537 / 1,843 ms | 32.6 / 549 ms |
| 300/s | 2.1 / 65.5 ms | 2,589 / 5,014 ms | 5,603 / 7,799 ms |
| 400/s | 2.4 / 95.7 ms | **kept 74/s**: 12,724 × 504, 6,772 × 429 | **kept 33/s**: 22,010 × 408 |
| 500/s | 2.7 / 133 ms | **kept 6/s** | **kept 0/s** |

At 400/s on MongoDB, Wow 10 refused work instead of hanging, with `429 wow.command.busy` and `504 wow.wait.timeout`. It paused the saga's partitions under the systemic failure rule (D243) 172–244 times and resumed them. A few commands, 28–50 per step, answered `503 wow.command.unknown`.

PostgreSQL reached no cliff within the steps measured.

## Cluster and hand-over (E5, L4)

Three nodes, requests round-robin, 90 s measured. In the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later.

The rerun after the signal fix ([39893318539](https://github.com/Ahoo-Wang/Wow/actions/runs/39893318539), AMD EPYC 7763):

| Backend | Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Lowest second |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | E1 steady | 150/s | 150/s | 0 | 3.2 ms | 9.5 ms | 43.0 ms | 150/s |
| PostgreSQL | E1 hand-over | 150/s | 133/s | 1,533 | 3.8 ms | 145 ms | 1,589 ms | 100/s |
| PostgreSQL | E3 steady | 50/s | 50/s | 0 | 6.4 ms | 17.0 ms | 63.7 ms | 50/s |
| PostgreSQL | E3 hand-over | 50/s | 44/s | 503 | 8.1 ms | 655 ms | 1,919 ms | 33/s |
| MongoDB | E1 steady | 150/s | 150/s | 0 | 6.0 ms | 64.0 ms | 334 ms | 150/s |
| MongoDB | E1 hand-over | 150/s | 131/s | 1,697 | 12.0 ms | 1,475 ms | 4,325 ms | 100/s |
| MongoDB | E3 steady | 50/s | 50/s | 0 | 12.2 ms | 60.7 ms | 162 ms | 50/s |
| MongoDB | E3 hand-over | 50/s | 44/s | 538 | 19.7 ms | 2,736 ms | 7,384 ms | 33/s |

**Wait signals between nodes: found and fixed.** The first L4 run ([39722970362](https://github.com/Ahoo-Wang/Wow/actions/runs/39722970362)) dropped 287–1,378 signals per run, and while 100,000 accounts were seeded at a concurrency of 256, 132 (PostgreSQL) and 115 (MongoDB) committed creations answered `504 wow.wait.timeout`, so both E5 jobs failed.
- A command that arrives at one node and commits on its partition's owner is answered by a signal back to the first node, sent over the internal port.
- The seeding and the prime ran the three nodes, Kafka and the store at full speed on the runner's 3 free cores (each node above 0.7 cores, CPU pressure 830–920 ms/s, no steal, iowait about 0.5%). The event loops could not get a core within the signal's fixed 1 s limit, so the signals were dropped. It was not a defect of low load: during the measured phases each node used about 0.1 cores. The drop counts were also cumulative since the nodes started, so the seeding's drops were reported in the steady rows.
- A signal now gets its wait's deadline, and one whose deadline has passed is not sent (`expired`) (#4822). The report counts the node signals of the measured phase only (#4823).
- The rerun dropped no signal and timed out no wait, in the seeding, the prime or the measured phases. Two of 100,000 deposits in one prime (PostgreSQL, before the E3 steady run) were answered as failed: the forward to the owner ended `unknown` while the runner was saturated, and the client was told so rather than told a timeout for a committed command.

**Hand-over.** The failed requests in the hand-over runs are the load generator's, not the cluster's. It keeps sending a third of the requests to the stopped node, and they fail as `ClosedChannelException`: about 1,500 at 150/s over the 20 s the node is down. A load balancer reading the readiness probe would stop routing to the node.

What the cluster itself shows:
- no second below half the rate;
- the worst second's p99 is 1.6–7.4 s while the stopped node's leases expire (TTL 10 s);
- 4–166 commands ran on the node that received them (`forwardFailed local`), as decided for an unreachable owner (D71);
- a few tail reads read the store (`tailRead gap`).

## Cold cache, a million accounts (L4)

| Backend | Scenario | Rate | p50 | p95 | p99 | Worst-second p99 | CPU ms/op |
| --- | --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | E1 | 200/s | 1.6 ms | 3.9 ms | 6.4 ms | 32.1 ms | 4.25 |
| PostgreSQL | E4 point read | 500/s | 0.5 ms | 1.4 ms | 2.8 ms | 9.3 ms | 1.06 |
| MongoDB | E1 | 200/s | 5.9 ms | 54.3 ms | 110 ms | 197 ms | 10.87 |
| MongoDB | E4 point read | 500/s | 0.9 ms | 2.7 ms | 6.6 ms | 45.8 ms | 2.36 |

## Store round trips and allocation per operation (L1)

The domain on the kernel and the store in one JVM, with no HTTP, counted by the store's command listeners. Throughput here is indicative only.

| Backend | Scenario | Round trips/op | By command | Alloc/op | Throughput |
| --- | --- | --- | --- | --- | --- |
| PostgreSQL | E1 | 3.31 | statement 3.16 · commit 0.15 | 100 KiB | 2,352/s |
| PostgreSQL | E1 large | 3.34 | statement 3.18 · commit 0.16 | 230 KiB | 1,398/s |
| PostgreSQL | E3 | 5.46 | statement 5.24 · commit 0.22 | 243 KiB | 1,020/s |
| MongoDB | E1 | 6.02 | find 1.88 · aggregate 1.06 · findAndModify 0.97 · insert 0.93 · commitTransaction 0.93 · update 0.26 | 328 KiB | 523/s |
| MongoDB | E1 large | 6.04 | as E1 | 441 KiB | 491/s |
| MongoDB | E3 | 12.98 | find 3.27 · findAndModify 2.68 · insert 2.59 · commitTransaction 2.59 · aggregate 1.35 · update 0.49 | 712 KiB | 242/s |

Head and baseline agree to the second decimal. These numbers supersede `v10-counts-e1-e3`. That report was measured before R3-A, R3-B, P.16, D248 and D261.

## Room for optimization

The items below are ranked by expected gain. All are registered in [roadmap §6](../../../../documentation/designs/roadmap.md), registered only, not dispatched.

1. ~~**Wait signals between nodes time out (defect).**~~ Fixed (#4822); the E5 rerun dropped no signal (see the cluster section).
2. **Tail latency at moderate load.** The sustainable rate is only 37–50% of saturation on PostgreSQL and 16–22% on MongoDB.
   - E1 on PostgreSQL at 60% of saturation has p99 between 0.9 and 3.1 s with neither the server nor the database saturated.
   - Every grid run's second half differs from its first beyond the limits (`unstable`), so the warm-up does not reach the steady state either.
   - Find where the stalls are: saga waits, the Kafka hop, PostgreSQL commit coalescing, or the state cache's evictions.
3. **MongoDB appends.**
   - Against 9.x, Wow 10 is 2–4× slower at p50 at an equal low rate.
   - Its sustainable rates are a third of PostgreSQL's.
   - The partition counter (`findAndModify`) and the transaction commit account for about two of six round trips per write.
   - The candidate is owner-assigned sequences (D250 ③).
4. **Store round trips per operation.** E1 on PostgreSQL issues 3.16 statements, where a cached aggregate needs about one append. With 100,000 accounts against a cache of 4,096, most writes load the aggregate first. Break the statements down, then size the cache or batch the loads.
5. **Allocation and CPU per operation.**
   - E1 on PostgreSQL allocates about 100 KiB per operation and E3 about 243 KiB; MongoDB about three times that.
   - E3 costs about 5.6 ms of CPU at its sustainable rate.
   - The likely places are serialization, envelopes and payload copies.
6. **The harness.**
   - A seed with any failed creation fails the whole E5 run.
   - In the hand-over runs the load generator keeps sending to the stopped node.
   - The L1 suite's pairs table prints a `NaN` row.
   - The 30 s warm-up does not reach a steady state on these runners.

## Caveats

- **One shared 4-core runner per job.** The server, the store, Kafka and the load generator compete for the same cores, so absolute rates are far below a dedicated deployment. Production sizing needs a run on the target hardware.
- **The processor differs between jobs.** Only the pairs within one job (head and baseline, or Wow 10 and 9.x) are compared. Numbers from different jobs are shown side by side but not as ratios.
- **Latencies at a share of saturation are noisy here** (see the A/A check). Ratios of throughput, CPU and allocation per operation are the robust results.
- **The state cache is the default.** At 4,096 entries per context against 100,000 accounts, every scenario but Zipf runs with a mostly missing cache.

## Reproduce

```bash
gh workflow run bench.yml --ref main -f head_ref=main -f tier=L3 -f suites='ab,component,vs-9x'
```

```bash
gh workflow run bench.yml --ref main -f head_ref=main -f tier=L4
```

Each job uploads its results as an artifact `bench-result-<suite>`:
- `summary.md`, `suite.json` and per run `result.json`;
- the node, seed and prime logs;
- for L1, `component.json`.

The tables above are taken from those summaries and logs.

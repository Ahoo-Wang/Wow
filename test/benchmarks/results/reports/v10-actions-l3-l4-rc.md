# Wow 10 benchmark before the rc: L3 and L4 on GitHub Actions

The milestone refresh of roadmap §6 item 19, run on GitHub Actions on 2026-10-25 and 2026-10-26 with the code of `10.0.0-alpha.5`. It answers four questions:

- How the head compares with `10.0.0-alpha.4`, scenario by scenario (L3).
- What rate each scenario sustains with p99 within 200 ms (L4).
- How a three-node cluster behaves, including a node's hand-over (L4).
- How a cold cache over a million accounts behaves (L4).

The previous Actions report, [v10-vs-9x](https://github.com/Ahoo-Wang/Wow/blob/26f1fa05030db5bcaeae70c34b6531dd1938c821/test/benchmarks/results/reports/v10-vs-9x.md), measured the code of `10.0.0-alpha.4` itself. Between the two lie D268–D280 and roadmap §6 items 3–17.

## Summary

- **No regression against `10.0.0-alpha.4` in L3.**
  - Every one of the 20 suites has a higher closed-loop saturation on the head: 1.13–6.87 times.
  - At one rate for both arms, the head allocates less per operation in all 20 (0.54–0.92×). It uses less CPU per operation in 15 and as much in the other five (0.98–1.05×, all at 1k accounts: four on PostgreSQL and Large on MongoDB).
  - None of the ratios is a calibrated verdict: no suite has an A/A noise threshold yet (`test/benchmarks/noise.json`). The pairs share a runner, so the CPU model cancels out.
- **The largest gains are where alpha.4 missed its state cache.** At 100k accounts alpha.4 held 4,096 states per context; the head holds about a tenth of the heap in bytes, about 105,000 states, so every account (roadmap §6 item 13, D274).
  - At an equal rate on 100k accounts, p50 is 0.001–0.72× and p99 0.015–0.86× alpha.4's.
  - E3 saturation 1.89× (MongoDB) and 1.93× (PostgreSQL); E1 at most 1.60× (MongoDB, where alpha.4's search reached its highest concurrency) and 1.75× (PostgreSQL).
- **Hot aggregates gain the most.** E1 with Zipf keys on MongoDB saturates at 3.99× alpha.4 (100k) and 6.87× (1k), from 533/s to 2,123/s and from 441/s to 3,029/s. A hot aggregate's queued commands now commit with one append (D273, §6 item 10); this is that item's Actions check.
- **With 1k accounts, which every cache holds on both arms, PostgreSQL gains less.**
  - Saturation is 1.13–1.65× and CPU per operation 0.98–1.05×; latency at an equal rate is in the noise.
  - E3 on PostgreSQL at 1k is the one pair where the head's equal-rate latency is higher: p50 1.35× (0.85–1.85) and p99 1.67× (0.94–2.41), with both pairs on either side of 1. It saturates at 1.65× alpha.4 all the same.
  - MongoDB at 1k, Zipf aside, still gains 1.31–2.06× in saturation, from owner-assigned sequences (D271) and merged groups (D275).
- **Sustainable rates (≤ 1% errors, p99 ≤ 200 ms), one node on a 4-core runner:**
  - MongoDB: E1 1,152/s, E2 547/s, E3 363/s. Against alpha.4 on the same CPU model (EPYC 7763) that is 4.2×, 3.8× and 4.7×.
  - PostgreSQL: E1 1,165/s and E3 751/s. Against alpha.4 that is 1.39× and 1.64×, but on a different CPU model, and E1 is within the 1,124–1,318/s the runs since the settle step found on three CPU models.
  - The share of saturation they reach rose on MongoDB, from 16–22% to 41–56%; on PostgreSQL it is 47% for both.
- **No cliff up to 500/s.** E3 kept up at every step on both backends. On alpha.4, MongoDB collapsed at 400/s.
- **Cluster.** The steady runs and the hand-overs carried the full rate, with no failed request except four in MongoDB's E3 hand-over. A hand-over's worst second has p99 0.76–6.7 s.
- **Two tails to watch; neither is a confirmed regression.**
  - Cold PostgreSQL E1 has p99 16.8 ms against 6.4 ms on alpha.4, on the same CPU model. The rest of the run is better.
  - The cluster's steady E3 has p99 147 ms against 61 ms on MongoDB, on the same CPU model.
  - Each is one run against one run, not a pair, and both come from a few seconds (see the L4 sections).

## Setup

| | |
| --- | --- |
| Runs | L3 [40244738772](https://github.com/Ahoo-Wang/Wow/actions/runs/40244738772) (`tier=L3`); L4 [40244742539](https://github.com/Ahoo-Wang/Wow/actions/runs/40244742539) (`tier=L4`). Workflow Bench (`bench.yml`), dispatched on `main`, every other input at its default |
| Head | L3: `main` at `c48c5d519`. L4: `main` at `4aa5e0d60`; it queued behind L3 and resolved `main` when it started. Both have the code of `10.0.0-alpha.5` (`776262411`). Between them lie only the release's version numbers, documentation and benchmark reports |
| Baseline | L3: `v10.0.0-alpha.4` (`c57cedbc28`), the newest `v10.*` tag when the runs were dispatched, chosen by the workflow (`base_ref` empty). L4 has no baseline arm; it is compared with alpha.4's L4 run, [39722970362](https://github.com/Ahoo-Wang/Wow/actions/runs/39722970362), and alpha.4's E5 rerun, [39893318539](https://github.com/Ahoo-Wang/Wow/actions/runs/39893318539) |
| Harness | Built from the head for both arms: the load generator, the settle step after the prime and the hand-over's rerouting are the same on both sides of a pair |
| Runners | GitHub-hosted, 4 cores and 16 GB each. L3: AMD EPYC 7763 (12 suites), 9V45 (5), 9V74 (2), Intel Xeon 6973P-C (1). L4: EPYC 7763 (5), 9V45 (3). No Xeon 8370C or 8573C in either run |
| Topology | One node unless named. The server, the load generator, the store and Kafka share the runner. Heap 1 GB, G1 |
| Data | L3 at 1k accounts (every cache holds them) and 100k (primed). L4 at 100k (primed), cold at 1,000,000 (not primed) |
| Method | Warm-up 30 s and measurement 40 s (E5: 90 s). L3: each arm's closed-loop saturation, then 3 calibrated rounds per arm (each at its own share of saturation, 34–80%) and 2 rounds at the lower of the two rates, alternating head and base on one runner. L4: sustainable rates by bisection, fixed E3 steps, E5 steady and hand-over |

## L3: the head against `10.0.0-alpha.4`

Ratios are head over base, median (min–max) of the pairs on one runner. Saturation is from the closed-loop search, the rest from the two rounds at an equal rate. Lower is better except saturation.

### 100k accounts

| Suite | Runner | Saturation base → head | × | CPU ms/op | Alloc/op | p50 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| E1 MongoDB, uniform | EPYC 9V45 | ≥ 2,222 → 3,562/s | ≤ 1.60 | 0.77 (0.76–0.78) | 0.54 | 0.011 | 0.078 |
| E1 MongoDB, Zipf | EPYC 7763 | 533 → 2,123/s | 3.99 | 0.82 (0.81–0.82) | 0.55 | 0.17 | 0.019 |
| E1 PostgreSQL | EPYC 9V45 | 2,487 → 4,361/s | 1.75 | 0.90 (0.86–0.93) | 0.73 | 0.018 | 0.055 |
| E2 MongoDB | EPYC 9V74 | 987 → 1,496/s | 1.52 | 0.88 (0.87–0.88) | 0.64 | 0.007 | 0.027 |
| E3 MongoDB | EPYC 7763 | 518 → 977/s | 1.89 | 0.90 | 0.58 | 0.015 | 0.090 |
| E3 PostgreSQL | EPYC 9V45 | 1,250 → 2,410/s | 1.93 | 0.92 | 0.75 | 0.16 | 0.38 |
| Mixed MongoDB | EPYC 7763 | 843 → 1,910/s | 2.27 | 0.83 (0.80–0.86) | 0.57 | 0.36 | 0.23 |
| Mixed PostgreSQL | EPYC 7763 | 1,911 → 2,404/s | 1.26 | 0.93 (0.92–0.94) | 0.79 | 0.72 | 0.86 |
| Large MongoDB | EPYC 7763 | 1,038 → 1,420/s | 1.37 | 0.63 (0.59–0.67) | 0.74 | 0.001 | 0.015 |
| Large PostgreSQL | EPYC 7763 | 1,126 → 1,356/s | 1.20 | 0.90 (0.89–0.92) | 0.87 | 0.33 | 0.52 |

### 1k accounts

| Suite | Runner | Saturation base → head | × | CPU ms/op | Alloc/op | p50 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| E1 MongoDB, uniform | EPYC 9V45 | 2,087 → 3,728/s | 1.79 | 0.80 (0.80–0.81) | 0.62 | 0.066 | 0.19 |
| E1 MongoDB, Zipf | EPYC 9V74 | 441 → 3,029/s | 6.87 | 0.77 (0.76–0.78) | 0.55 | 0.25 | 0.043 |
| E1 PostgreSQL | EPYC 7763 | 2,087 → 2,358/s | 1.13 | 0.98 (0.97–0.99) | 0.82 | 0.82 | 1.02 (0.20–1.85) |
| E2 MongoDB | EPYC 9V45 | 1,315 → 1,718/s | 1.31 | 0.93 (0.92–0.95) | 0.70 | 0.13 | 0.28 |
| E3 MongoDB | EPYC 7763 | 523 → 950/s | 1.82 | 0.94 (0.93–0.95) | 0.65 | 0.031 | 0.17 |
| E3 PostgreSQL | EPYC 7763 | 787 → 1,302/s | 1.65 | 1.05 (0.98–1.12) | 0.81 | 1.35 (0.85–1.85) | 1.67 (0.94–2.41) |
| Mixed MongoDB | Xeon 6973P-C | 1,589 → 3,271/s | 2.06 | 0.84 (0.84–0.85) | 0.70 | 0.57 | 0.26 |
| Mixed PostgreSQL | EPYC 7763 | 1,738 → 2,241/s | 1.29 | 0.99 (0.99–1.00) | 0.89 | 0.96 | 0.78 |
| Large MongoDB | EPYC 7763 | 1,158 → 1,741/s | 1.50 | 0.99 (0.99–0.99) | 0.75 | 0.056 | 0.21 |
| Large PostgreSQL | EPYC 7763 | 1,144 → 1,288/s | 1.13 | 1.03 (1.03–1.04) | 0.92 | 1.01 | 1.00 |

### Reading the pairs

Improvements, and the roadmap items that explain them:

- **The state cache now holds the 100k accounts** (roadmap §6 item 13, D274), so writes load the aggregate from the store far less often.
  - alpha.4's cache held 4,096 states per context: most of its writes at 100k loaded the aggregate from the store.
  - This is why the latency ratios at 100k are one to two orders of magnitude, and larger than at 1k.
  - At 1k neither arm misses its cache, and the latency ratios on PostgreSQL are around 1.
- **The loads that remain take fewer round trips (D268, D269).** A miss reads the snapshot and the tail in one statement, and SQL is built once per shape. alpha.4 issued one statement for each.
- **MongoDB writes are cheaper.**
  - Owner-assigned sequences replace the partition counter and the multi-document transaction (D271, §6 item 3).
  - The append queue is on by default (D248 ④).
  - Several partitions' groups merge into one `insertMany` (D275, §6 item 14).
  - The log position is the `_id` (D276); the tail reads and snapshots are built as BSON (§6 item 8).
  - Together these explain why MongoDB at 1k, with no cache effect and Zipf aside, still saturates at 1.31–2.06× alpha.4 and allocates 0.55–0.75× as much.
- **Hot aggregates commit their queued commands with one append** (D273, §6 item 10). This drives E1 with Zipf keys to 3.99× (100k) and 6.87× (1k) alpha.4's saturation. Locally, alpha.4's throughput stopped growing from a concurrency of 32 up, because the hottest lane committed one command at a time.
- **PostgreSQL commits several partitions' groups with one statement** (D280, §6 item 15). With the cache, this accounts for E3 on PostgreSQL at 1.65× (1k) and 1.93× (100k).

What did not improve, and where the head is no better:

- **PostgreSQL at 1k:**
  - E1, Mixed and Large saturate at 1.13–1.29×.
  - Their CPU per operation is 0.98–1.03× and their latency at an equal rate 0.78–1.02×.
  - Nothing in §6 made a cached PostgreSQL write cheaper on the node; the gains came from fewer loads and fewer commits.
- **E3 on PostgreSQL at 1k** has the head's equal-rate p50 at 1.35× and p99 at 1.67×.
  - The two pairs straddle 1: p50 0.85 and 1.85, p99 0.94 and 2.41. CPU per operation 0.98 and 1.12.
  - At 265.7/s the base is at a third of its saturation and the head at a fifth, where the earlier A/A check of v10-vs-9x found latencies differing by up to 6× between runs of the same code.
  - This is not a regression at the evidence's level. A paired local ABAB at 1k would settle it if it matters.

Where a ratio is bounded by the setup rather than by Wow:

- **The load generator saturated its one core** on some head rounds, so those rounds measured the generator:
  - E1 MongoDB Zipf 100k (r1–r3, 627 `generator-overload` in r1);
  - Mixed PostgreSQL 100k (r1–r3);
  - E2 MongoDB 1k (r1–r2, 4,945 `generator-overload` in r1).
  - The head's saturation there is a lower bound.
- **Large on MongoDB at 100k:**
  - Both arms refused work at their calibrated rate (80% for the head, 60% for the base): `429 wow.command.busy` 9–10% of the head's operations, and on the base `429`, `504 wow.wait.timeout` and `503` for 9–33%.
  - At the equal rate of 622.7/s the base did not keep up (throughput ratio 1.41). That pair's latency ratio of 0.001 compares a node that kept up with one that did not.
- **The calibrated rounds run each arm at its own share of saturation**, so their latency ratios compare unequal loads and are not read. For example, Mixed PostgreSQL 100k ran the base at 45% and the head at 60%.
- **Not sustained.** Three calibrated arms did not hold their rate: E1 PostgreSQL 1k base, Mixed MongoDB 1k head and Mixed PostgreSQL 1k head. One base round of E1 PostgreSQL at 1k failed 1,550 of 28,180 operations (`504`, `429`).

Checks across all 20 suites:
- No partition paused.
- No head allocated more than 5% over its baseline.
- 140 rounds were `unstable` (75 base, 65 head): the second half differed from the first beyond ±25%. The 30 s warm-up does not reach a steady state on these runners (§6 item 6).

## L4: sustainable rates

The highest open-loop rate with at most 1% errors and p99 within 200 ms, found by bisection; one node, 100k accounts, primed.

| Backend | Scenario | Sustainable | p50 | p99 | CPU ms/op | Saturation | Share | alpha.4 (39722970362) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MongoDB | E1 | **1,152/s** | 12.9 ms | 99.3 ms | 2.37 | 2,048/s | 56% | 276/s, 9.07 ms/op, 22% |
| MongoDB | E2 | **547/s** | 14.1 ms | 192 ms | 4.74 | 1,095/s | 50% | 145/s, 14.73 ms/op, ≤ 19% |
| MongoDB | E3 | **363/s** | 13.4 ms | 153 ms | 7.54 | 893/s | 41% | 78/s, 27.59 ms/op, 16% |
| PostgreSQL | E1 | **1,165/s** | 4.4 ms | 83.5 ms | 2.03 | 2,485/s | 47% | 837/s, 2.76 ms/op, 37% |
| PostgreSQL | E3 | **751/s** | 17.8 ms | 200 ms | 3.53 | 1,602/s | 47% | 458/s, 5.60 ms/op, 50% |

How to read the change against alpha.4:

- **MongoDB ran on an EPYC 7763 both times**, so the 3.8–4.7× is the code and the harness.
  - The settle step after the prime came after alpha.4 (#4824). The run that introduced it (39870041854, EPYC 9V45, with D268 and D269 too) found 423, 297 and 186/s on MongoDB.
  - After D271, run 39929517589 found 771, 569 and 259/s on an EPYC 9V45.
  - Today's E1 and E3 are higher still: the state cache (§6 item 13), D273 and D275. E2, a write and a read, is where it was after D271.
- **PostgreSQL moved from a Xeon 8370C to an EPYC 7763.**
  - The runs since the settle step put E1 at 1,318/s (EPYC 9V45, 39870041854) and 1,124/s (Xeon 8573C, 39888333484). Today's 1,165/s is within that spread, so the 1.39× against alpha.4 is not claimed for the code.
  - E3 at 751/s is above every earlier run (458, 580, 460/s), and its CPU per operation fell from 5.60 to 3.53 ms. That is consistent with D280 and the cache.
- **Tail latency still sets the usable capacity** (§6 item 2), but MongoDB now reaches half its saturation instead of a fifth.

## L4: cliff

E3 in fixed open-loop steps of 40 s each.

| Rate | PostgreSQL p50 / p99 | MongoDB p50 / p99 | alpha.4 PostgreSQL p50 / p99 | alpha.4 MongoDB p50 / p99 |
| --- | --- | --- | --- | --- |
| 50/s | 3.5 / 9.5 ms | 4.5 / 17.2 ms | 2.6 / 13.7 ms | 12.0 / 90 ms |
| 100/s | 3.6 / 12.3 ms | 5.2 / 34.6 ms | 2.3 / 14.9 ms | 21.8 / 254 ms |
| 200/s | 2.8 / 38.4 ms | 4.7 / 60.4 ms | 2.2 / 34.3 ms | 537 / 1,843 ms |
| 300/s | 2.8 / 27.3 ms | 4.4 / 110 ms | 2.1 / 65.5 ms | 2,589 / 5,014 ms |
| 400/s | 4.1 / 59.9 ms | 9.3 / 167 ms | 2.4 / 95.7 ms | **kept 74/s** |
| 500/s | 7.1 / 64.5 ms | 9.9 / 182 ms | 2.7 / 133 ms | **kept 6/s** |

Every step kept up with no failure on both backends. No partition paused, no tail fell back to the store, and no command was refused as busy.

The runners swapped between alpha.4's run and this one: PostgreSQL moved from an EPYC 9V45 to a 7763, MongoDB from a 7763 to a 9V45.
- MongoDB went from a collapse at 400/s to p99 182 ms at 500/s. No CPU change explains that; the cheaper writes (D271, D275) and the cache do.
- PostgreSQL's p50 at low rates is 1 ms higher than alpha.4's and its p99 at 400–500/s lower. These are single runs on different CPU models, and neither is claimed.

## L4: cluster and hand-over (E5)

Three nodes, requests round-robin, 90 s measured. In the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later. MongoDB ran on an EPYC 7763; PostgreSQL on an EPYC 9V45. alpha.4's E5 rerun (39893318539) ran both on an EPYC 7763.

| Backend | Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Lowest second | alpha.4: throughput, p99, worst second |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | E1 steady | 150/s | 150/s | 0 | 2.6 ms | 12.7 ms | 117 ms | 150/s | 150/s, 9.5 ms, 43 ms |
| PostgreSQL | E1 hand-over | 150/s | 150/s | 0 | 2.9 ms | 71.7 ms | 758 ms | 150/s | 133/s, 145 ms, 1,589 ms |
| PostgreSQL | E3 steady | 50/s | 50/s | 0 | 6.2 ms | 36.6 ms | 238 ms | 50/s | 50/s, 17.0 ms, 64 ms |
| PostgreSQL | E3 hand-over | 50/s | 50/s | 0 | 7.2 ms | 90.1 ms | 1,205 ms | 50/s | 44/s, 655 ms, 1,919 ms |
| MongoDB | E1 steady | 150/s | 150/s | 0 | 3.7 ms | 52.7 ms | 205 ms | 150/s | 150/s, 64.0 ms, 334 ms |
| MongoDB | E1 hand-over | 150/s | 150/s | 0 | 6.1 ms | 1,401 ms | 4,358 ms | 150/s | 131/s, 1,475 ms, 4,325 ms |
| MongoDB | E3 steady | 50/s | 50/s | 0 | 8.2 ms | 147 ms | 449 ms | 50/s | 50/s, 60.7 ms, 162 ms |
| MongoDB | E3 hand-over | 50/s | 50/s | 4 | 13.4 ms | 3,375 ms | 6,716 ms | 48/s | 44/s, 2,736 ms, 7,384 ms |

- **Hand-over.** The hand-overs now carry the full rate.
  - The load generator skips a node whose connections fail, as a load balancer with passive health checks would, and sends the refused operations to the next node (§6 item 6). Before, about 1,500 operations failed as `ClosedChannelException` against the stopped node.
  - That is the harness, the same for any code. What the cluster itself shows: no second below half the rate, the worst second's p99 between 0.76 and 6.7 s while the stopped node's leases expire (TTL 10 s), and 5–16 commands that ran on the node that received them (`forwardFailed local`, D71).
  - MongoDB's E3 hand-over failed 4 of 4,500: 3 committed commands whose wait timed out (`504 wow.wait.timeout`) and one `IOException`.
- **The steady runs on MongoDB.**
  - The median got better: E1 p50 6.0 → 3.7 ms, E3 12.2 → 8.2 ms. CPU per operation fell (E1 13.2 → 11.6 ms, E3 39.5 → 35.8 ms), and so did allocation (E1 346 → 234 KB, E3 992 → 680 KB).
  - **E3's tail got worse on the same CPU model:** p99 60.7 → 147 ms, worst second 162 → 449 ms.
  - The median second has p99 19 ms. The tail comes from 8 seconds above 100 ms, all within the first 37 of the 90.
  - With 50 operations a second, p99 is the worst 45 of 4,500. One run against one run does not separate code from runner. It is recorded here to be checked in the next E5 run, not as a regression.
- **The steady runs on PostgreSQL.** E3's p99 went from 17.0 to 36.6 ms and E1's from 9.5 to 12.7 ms, but the runner changed from an EPYC 7763 to a 9V45, so neither is claimed.

## L4: cold cache, a million accounts

| Backend | Scenario | Rate | p50 | p95 | p99 | Worst-second p99 | CPU ms/op | Alloc/op | alpha.4: p50 / p99, CPU ms/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | E1 | 200/s | 1.4 ms | 4.4 ms | 16.8 ms | 78.3 ms | 3.98 | 113 KB | 1.6 / 6.4 ms, 4.25 |
| PostgreSQL | E4 point read | 500/s | 0.4 ms | 1.3 ms | 2.5 ms | 4.2 ms | 1.01 | 33 KB | 0.5 / 2.8 ms, 1.06 |
| MongoDB | E1 | 200/s | 3.6 ms | 21.5 ms | 68.1 ms | 241 ms | 9.36 | 234 KB | 5.9 / 110 ms, 10.87 |
| MongoDB | E4 point read | 500/s | 0.9 ms | 2.5 ms | 6.2 ms | 109 ms | 2.44 | 71 KB | 0.9 / 6.6 ms, 2.36 |

PostgreSQL ran on an EPYC 9V45 and MongoDB on an EPYC 7763 both times.

- MongoDB's cold E1 improved throughout: p50 −39%, p99 −38%, CPU per operation −14%, allocation 375 → 234 KB.
- **PostgreSQL's cold E1 p99 is worse**, 6.4 → 16.8 ms, while its p50 and CPU per operation improved.
  - The per-second p99 is 2–9 ms except in two bursts: seconds 0–2 (14–78 ms) and 23–26 (12–57 ms).
  - These are single 40 s runs of 8,000 operations, not a pair, so this is not called a regression. It is recorded to be checked by the next L4 run or a local cold ABAB.
- Point reads are as before on both backends.

## Caveats

- **One shared 4-core runner per job.** The server, the store, Kafka and the load generator compete for the same cores, so absolute rates are far below a dedicated deployment's. Production sizing needs a run on the target hardware.
- **The processor differs between jobs**, and the L4 comparisons cross jobs.
  - Only L3's head and baseline share a runner, so only its ratios are the code's.
  - The L4 comparisons with alpha.4 name the CPU model of both sides. Where it changed, no change is claimed.
- **No ratio is calibrated.** No suite has an A/A noise threshold yet, so the significance table of the run reports every ratio as `uncalibrated`. The ratios above are far from 1 except where this report says otherwise.
- **Latencies at a share of saturation are noisy on these runners** (v10-vs-9x's A/A check: up to 6×). Throughput, CPU and allocation per operation are the robust results.
- **The harness is the head's for both arms**, so L3's ratios are of the server code. The L4 comparisons with alpha.4's run also include the harness changes since: the settle step and the hand-over's rerouting.

## Data

[`v10-actions-l3-l4-rc/`](v10-actions-l3-l4-rc/) holds the raw results:
- each run's report, `l3/report.md` and `l4/report.md`: every round, probe and anomaly;
- per suite, `summary.md` and `suite.json`;
- per round or probe, `result.json`: latencies, per-second series, histogram, resources and environment, commit included;
- per closed-loop search, `saturation.json`.

The node, seed, prime and settle logs, and the server distributions, stay in the runs' artifacts.

## Reproduce

```bash
gh workflow run bench.yml --ref main -f head_ref=main -f tier=L3
```

```bash
gh workflow run bench.yml --ref main -f head_ref=main -f tier=L4
```

Without `base_ref`, L3's baseline is the newest `v10.*` tag with the benchmark server; to measure against alpha.4 again, add `-f base_ref=v10.0.0-alpha.4`. Each job uploads `bench-result-<suite>`; the Report job uploads `e2e-bench-report`.

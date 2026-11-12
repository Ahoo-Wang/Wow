# Wow 10 on GitHub Actions before the rc: L3 and L4

The L3 and L4 runs before the release candidate (roadmap §6 item 19), on GitHub Actions on 2026-11-11, with
the follow-up runs they called for. They answer four questions:

- How the head compares with `v10.0.0-beta.1`, scenario by scenario (L3).
- What rate each scenario sustains with p99 within 200 ms (L4).
- How a three-node cluster behaves, including a node's hand-over (L4).
- How a cold cache over a million accounts behaves (L4).

The report before this one, [v10-actions-l3-l4-rc](https://github.com/Ahoo-Wang/Wow/blob/1357642891a5a458bdb8fc7adc364af7bcb8ee21/test/benchmarks/results/reports/v10-actions-l3-l4-rc.md),
measured the code of `10.0.0-alpha.5`. L3 now measures against the newest tag, `v10.0.0-beta.1`, 506 commits before
the head.

## Summary

- **One difference against `v10.0.0-beta.1` stays open: E1 on MongoDB with 100k accounts.**
  - The head's p99 at an equal rate is higher in every run against the tag: 1.49 in L3, beyond the suite's noise, and
    1.20–1.61 in the three ABAB runs since on EPYC 7763 runners.
  - In the last run, after #6041, the p99 of 1.195 and the p50 of 1.407 are both within the suite's noise. But the p50
    is higher in all four pairs.
  - CPU per operation is level (1.01–1.02), and so are MongoDB's commands per operation. The store is at or near its
    one core in both arms.
  - Ruled out: the access walk and the access follower's reads.
    - On the head and the tag, the walk stamps nothing in these end-to-end runs: its claim reads zero heads. It did
      run in one arm of one run, #5394's parent in 43067146468 (see below).
    - The follower's reads (RT-9) are ruled out by an A/A run with the follower's memory raised.
  - Not confirmed: the two access indexes #5394 adds to the log.
    - In an ABAB against its parent, with the parent's walk held back, #5394's CPU per operation is 0.974 and its p99
      0.983 (medians of four pairs, at about 2,090/s on EPYC 9V45 runners).
    - The difference itself was measured on EPYC 7763 runners at about 1,100/s.
  - No cause is stated.
- **E3 on MongoDB with 100k accounts** was beyond noise in L3 (p99 1.85, n=2). It is not there again in the ABAB
  (1.048, n=4), and leaves the list.
- **The other L3 suites:**
  - The other "beyond noise" verdicts follow from how the closed-loop search stopped and how the calibrated rates
    follow it (see "Two artifacts of the search").
  - CPU per operation at an equal rate is 0.97–1.07 in all 20 suites.
  - Allocation per operation is 0.99–1.08.
- **The access walk ran under the measured phase, and #6041 removes it.**
  - In the component suites, it issued four statements per walked aggregate at about 1 per 100 operations.
  - On #6041's head they are at 0.
- **Snapshot upserts per operation on PostgreSQL are 11–13% above the tag's.**
  - Before #6041 they were 20–23% above it.
  - What is left is #5420's smaller batches: snapshot writes no longer wait for the WAL flush.
  - #5420 halves the WAL syncs per operation at no cost in throughput, p99 or PostgreSQL's CPU (Check 2).
- **Past the knee, E3 is accepted and then times out.**
  - At 4,096 clients the head fails E3 by waits that ran out after 10 s (504), not by refusals at admission (429).
  - At 1k accounts, `v10.0.0-beta.1` (in L3) and `b435768e49` (40 commits later) do the same.
  - At 100k accounts on MongoDB, `b435768e49` refuses by 429. The head's search there stopped at 4 clients, so it has
    no step to compare.
  - X-118 was then found locally to start at #5533 and is closed by #6070. These runs don't measure that fix.
- **Sustainable rates (≤ 1% errors, p99 ≤ 200 ms), one node on a 4-core runner:**
  - MongoDB: E1 1,354/s, E2 677/s, E3 486/s. That is 1.18–1.34× alpha.5's on the same CPU model.
  - PostgreSQL: E1 2,610/s, E3 742/s. Its runner changed, so no change is claimed.
- **No cliff up to 500/s.** E3 kept up at every step on both stores.
- **The two tails the last report left open are not there again:**
  - cold PostgreSQL E1 p99 is 6.8 ms (16.8 ms at alpha.5);
  - three-node MongoDB E3 steady p99 is 49.7 ms (147 ms at alpha.5).
  - Each is one run against one, so they are closed by their absence, not measured.
- **MongoDB's E3 hand-over failed 30 of 4,500 commands,** against 4 at alpha.5: 29 waits timed out on committed
  commands and 1 node was closing. That is one hand-over against one, and no cause is stated. PostgreSQL's hand-overs
  failed none.

## Setup

| | |
| --- | --- |
| Runs | L3 [42973046096](https://github.com/Ahoo-Wang/Wow/actions/runs/42973046096) (`tier=L3`) and L4 [42973320597](https://github.com/Ahoo-Wang/Wow/actions/runs/42973320597) (`tier=L4`), dispatched with `head_ref=main` and every other input at its default, from a branch cut from `main`. The follow-up runs are listed in their own section |
| Head | L3: `main` at `a7c372ad3e`. L4: `main` at `d8284109df`, which differs from `a7c372ad3e` in documentation only |
| Baseline | L3: `v10.0.0-beta.1` (`22f8ac19cb`), the newest `v10.*` tag with the benchmark server (`base_ref` empty). L4 has no baseline arm. It is compared with alpha.5's L4 run, [40244742539](https://github.com/Ahoo-Wang/Wow/actions/runs/40244742539), the previous report's |
| Harness | Built from the head for both arms |
| Runners | GitHub-hosted, 4 cores and 16 GB each, JDK 25.0.5 (Temurin) on every job. L3: AMD EPYC 7763 (10 suites), 9V74 (4), 9V45 (2), Intel Xeon 8370C (4). L4: EPYC 7763 (5), 9V45 (2), 9V74 (1) |
| Topology | One node unless named. The server, the load generator, the store and Kafka share the runner. Heap 1 GB, G1 |
| Data | L3 at 1k accounts (every cache holds them) and 100k (primed). L4 at 100k (primed), cold at 1,000,000 (not primed) |
| Method | Warm-up 30 s and measurement 40 s (E5: 90 s). L3: each arm's closed-loop saturation, then 3 calibrated rounds per arm (each at its own share of saturation, 34–80%) and 2 rounds at the lower of the two rates, alternating head and base on one runner. L4: sustainable rates by bisection, fixed E3 steps, E5 steady and hand-over |
| Noise | 10 of L3's 20 suites (every 100k suite) have an A/A noise threshold (`test/benchmarks/noise.json`). A ratio is beyond noise when its 95% bootstrap interval over the measured seconds clears it. The 1k suites are uncalibrated |

## L3: the head against `v10.0.0-beta.1`

Ratios are head over base, median (min–max) of the pairs on one runner. Saturation is from each arm's closed-loop
search, with the concurrency of its peak. The rest is from the two rounds at an equal rate. Lower is better except
saturation. The verdicts are the run's significance table: throughput at the calibrated rates, p50 and p99 at the
equal rate.

### 100k accounts

| Suite | Runner | Saturation base → head (clients) | × | Equal rate | CPU ms/op | Alloc/op | p50 | p99 | Verdicts (throughput · p50 · p99) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| E1 MongoDB, uniform | EPYC 9V45 | 3,469 (2048) → 3,129 (2048) | 0.90 | 2,503.1/s | 1.020 (0.982–1.058) | 1.001 (0.979–1.023) | 2.011 (1.076–2.945) | 1.490 (1.128–1.853) | lower · higher · higher |
| E1 MongoDB, Zipf | EPYC 9V74 | 1,771 (1024) → 1,760 (1024) | 0.99 | 1,408.2/s | 1.013 (1.009–1.017) | 1.007 (1.005–1.009) | 1.298 (1.222–1.374) | 1.524 (1.089–1.959) | within noise · within noise · within noise |
| E1 PostgreSQL | EPYC 7763 | 1,990 (512) → 1,634 (64) | 0.82 | 1,306.9/s | 1.000 (1.000–1.001) | 1.035 (1.033–1.037) | 2.184 (1.985–2.382) | 0.916 (0.449–1.382) | lower · within noise · higher |
| E2 MongoDB | EPYC 7763 | 959 (2048) → 935 (1024) | 0.97 | 575.6/s | 0.986 (0.981–0.991) | 0.996 (0.978–1.015) | 1.188 (0.965–1.410) | 1.115 (0.681–1.549) | higher · within noise · within noise |
| E3 MongoDB | EPYC 9V74 | 681 (256) → 642 (256) | 0.94 | 513.3/s | 0.984 (0.972–0.997) | 0.987 (0.982–0.993) | 1.707 (1.559–1.854) | 1.851 (1.769–1.933) | lower · higher · higher |
| E3 PostgreSQL | EPYC 7763 | 590 (32) → 718 (512) | 1.22 | 430.5/s | 1.007 (1.006–1.007) | 1.050 (1.037–1.063) | 1.518 (1.407–1.629) | 0.957 (0.538–1.376) | lower · within noise · within noise |
| Mixed MongoDB | Xeon 8370C | 2,044 (256) → 1,912 (256) | 0.94 | 1,147.3/s | 0.994 (0.989–1.000) | 1.006 (1.002–1.009) | 1.113 (1.064–1.162) | 1.812 (1.268–2.356) | lower · within noise · within noise |
| Mixed PostgreSQL | EPYC 7763 | 2,188 (64) → 2,031 (64) | 0.93 | 914.1/s | 0.998 (0.989–1.008) | 1.026 (1.024–1.027) | 1.045 (1.040–1.051) | 1.926 (1.683–2.169) | lower · within noise · within noise |
| Large MongoDB | EPYC 7763 | 1,099 (2048) → 1,009 (256) | 0.92 | 605.3/s | 1.005 (0.984–1.025) | 1.014 (1.012–1.015) | 0.930 (0.922–0.939) | 0.881 (0.713–1.049) | lower · within noise · within noise |
| Large PostgreSQL | EPYC 9V74 | 814 (8) → 956 (64) | 1.17 | 651.0/s | 0.979 (0.932–1.026) | 1.000 (0.991–1.009) | 4.054 (0.827–7.281) | 1.474 (1.105–1.843) | higher · higher · within noise |

### 1k accounts

| Suite | Runner | Saturation base → head (clients) | × | Equal rate | CPU ms/op | Alloc/op | p50 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| E1 MongoDB, uniform | EPYC 7763 | 2,006 (2048) → 2,008 (2048) | 1.00 | 677.1/s | 0.989 (0.984–0.994) | 0.997 (0.963–1.031) | 1.413 (0.593–2.234) | 0.976 (0.561–1.391) |
| E1 MongoDB, Zipf | Xeon 8370C | 1,686 (1024) → 1,721 (1024) | 1.02 | 569.2/s | 0.998 (0.992–1.003) | 0.992 (0.989–0.996) | 1.744 (1.625–1.864) | 1.434 (1.378–1.490) |
| E1 PostgreSQL | EPYC 7763 | ≥ 2,060 (4096) → ≥ 2,166 (4096) | ≥ 1.05 | 695.2/s | 1.013 (1.005–1.022) | 1.024 (1.021–1.027) | 1.340 (1.047–1.634) | 1.470 (1.273–1.667) |
| E2 MongoDB | Xeon 8370C | ≥ 1,224 (4096) → ≥ 1,129 (4096) | ≥ 0.92 | 381.0/s | 0.990 (0.985–0.995) | 1.019 (1.018–1.020) | 1.117 (1.041–1.194) | 1.621 (1.266–1.977) |
| E3 MongoDB | EPYC 9V74 | 860 (1024) → 780 (1024) | 0.91 | 263.4/s | 1.066 (0.980–1.152) | 1.076 (1.020–1.133) | 0.814 (0.421–1.208) | 0.877 (0.433–1.321) |
| E3 PostgreSQL | EPYC 7763 | 578 (1024) → 584 (2048) | 1.01 | 195.2/s | 1.002 (1.001–1.004) | 1.059 (1.050–1.068) | 2.056 (1.479–2.633) | 3.002 (0.984–5.021) |
| Mixed MongoDB | EPYC 7763 | 1,515 (512) → 1,538 (256) | 1.02 | 511.4/s | 0.987 (0.982–0.991) | 0.993 (0.992–0.993) | 1.837 (1.623–2.051) | 2.654 (1.922–3.387) |
| Mixed PostgreSQL | Xeon 8370C | 1,380 (128) → 1,547 (256) | 1.12 | 465.7/s | 1.002 (0.996–1.008) | 1.027 (1.027–1.028) | 0.924 (0.829–1.020) | 0.883 (0.570–1.195) |
| Large MongoDB | EPYC 9V45 | 2,257 (1024) → 1,710 (32) | 0.76 | 769.4/s | 0.975 (0.973–0.977) | 1.005 (0.984–1.027) | 1.197 (0.983–1.410) | 1.256 (1.176–1.336) |
| Large PostgreSQL | EPYC 7763 | 892 (2048) → 975 (2048) | 1.09 | 301.0/s | 1.002 (1.002–1.003) | 1.024 (1.011–1.037) | 0.697 (0.475–0.919) | 1.267 (0.877–1.657) |

The 1k suites have no noise threshold, so every verdict is `uncalibrated`. Each latency ratio is two pairs at a third
of saturation or less.

### Reading the pairs

- **What does not move:** CPU per operation at the equal rate is 0.98–1.02 in 17 suites, 0.975–0.979 in Large on
  MongoDB at 1k and on PostgreSQL at 100k, and 1.07 (0.98–1.15) in E3 on MongoDB at 1k. Allocation per operation is
  0.99–1.08. The run flags two suites for
  allocating more than 5% above the base: E3 at 1k on MongoDB (×1.08) and on PostgreSQL (×1.06).
- **Latency beyond the noise at an equal rate, both pairs on the same side:**
  - E1 MongoDB uniform at 100k: p50 2.01 and p99 1.49. It is followed up below and stays open.
  - E3 MongoDB at 100k: p50 1.71 and p99 1.85. Absolute p99: head 832 and 848 ms, base 430 and 479 ms. The ABAB (n=4)
    finds p99 1.048 (0.829–1.153), within its ±9.1%, so it is not there again.
  - Large PostgreSQL at 100k: p50 4.05, whose pairs straddle 1 (0.83 and 7.28). Its base search peaked at 8 clients
    (next section).
- **At 1k, the PostgreSQL E1 search was 11–19% lower on the head at every step from 1 to 64 clients,** one 8 s step
  each.
  - The head was faster at 100k in the component suites (2,479 → 2,848/s).
  - The local check of one hot account found two runs of the same code differing by as much ([`m5max-snapshot` at `a7c372ad3e`](https://github.com/Ahoo-Wang/Wow/blob/d9b0781205aa5749bf7e3941dfcbb622c2eaac36/test/benchmarks/results/reports/m5max-snapshot.md)).
  - So this is one search, not a result.
- **Not checked by any suite:** D411 (Elasticsearch mappings), D412 (ClickHouse `_state`) and D413 (one write in
  flight per sink) are outside L3 and L4, which run no sink. D415 and D416 change no behaviour.

### Two artifacts of the search

Both are gaps of the tooling, listed again for X-106 at the end.

1. **A closed-loop search stops after two steps that gain under 5%.**
   - In L3, 26 of the 40 searches stopped before 4,096 clients.
   - Where one arm's search stopped well before the other's, the saturation ratio compares two stopping points, not
     two ceilings:
     - E1 PostgreSQL at 100k: the head peaked at 64 clients, dipped at 128 (1,634 → 1,503/s) and stopped. It never
       tried 256 or 512, where the base found 1,990/s. At the equal rate its CPU per operation is 1.000.
     - E3 PostgreSQL at 100k: the base stopped at 64, after its peak at 32.
     - Large PostgreSQL at 100k: the base stopped at 32, after its peak at 8.
     - Large MongoDB at 1k: the head stopped at 128, after its peak at 32.
     - Large MongoDB at 100k: the head stopped at 1,024, after its peak at 256.
   - The follow-up runs show the same on MongoDB at 100k. In the index run and #6041's E1 run, the head stopped at
     1,024 and 512 clients, where the base went on to 4,096. In the E3 knee run, the head's search stopped at 4
     clients.
2. **The calibrated rounds run each arm at its own share of its own saturation.**
   - Their "throughput lower" or "higher" verdicts compare the two rates the searches chose, not what each arm
     sustains.
   - Example: E3 PostgreSQL at 100k saturates 1.22× higher on the head, but the head was calibrated at 60% and the
     base at 80%, so its calibrated throughput is 0.91.
   - Only the equal-rate rows compare latency, and only at that rate.

A third observation for the same list: in the significance table a ratio sometimes lies outside its own interval. For
example, E1 PostgreSQL at 100k gives p99 0.718 [1.283–3.053], verdict "higher", and the index run gives p99 1.177
[0.820–1.170]. The two pairs of E1 PostgreSQL at 100k are 0.449 and 1.382. Such a verdict is not read here.

### Checks across all 20 suites

- No partition paused.
- **The load generator reached its per-target ceiling of 2,000 in flight** (`generator-overload`) in some rounds:
  - E1 MongoDB uniform at 100k, e2-head: 1,241 of 100,124.
  - E1 PostgreSQL at 1k: several head rounds and one base round.
  - Those rounds measured the generator in part.
- 147 rounds were `unstable` (72 base, 75 head): the second half differed from the first beyond the suite's limits.
- 22 rounds had a tail read the store, all on MongoDB Zipf (overflow 1–4), and on both arms.

## Follow-up runs on GitHub Actions

Each run is one dispatch of `bench.yml` from a `bench/` branch of its own, deleted when the run ended. The base is
`v10.0.0-beta.1` unless named.

| Run | Head | Base | Suites | Asked | Answer |
| --- | --- | --- | --- | --- | --- |
| [42987505636](https://github.com/Ahoo-Wang/Wow/actions/runs/42987505636) | `44eafe932d` | beta.1 | e3-mongo, e1-mongo-uniform at 100k, `repeats=4`, `profile=true` | Do L3's two MongoDB results repeat in an ABAB? | E3 no: p99 1.048 (0.829–1.153), within ±9.1%. E1 yes: p99 1.611 (1.129–2.662), beyond ±12.1%, higher in all four pairs, p50 1.537 |
| [42987605146](https://github.com/Ahoo-Wang/Wow/actions/runs/42987605146) | `44eafe932d` | beta.1 | component-mongo, component-pg at 100k | What does the head ask of the store per operation? | Appends level. Four statements only the head issues (the access walk), and more snapshot upserts on PostgreSQL |
| [42998336081](https://github.com/Ahoo-Wang/Wow/actions/runs/42998336081) | `669861fb45`, `wow.access.capacity=1000000`, `walk-rate=1` | beta.1 | e1-mongo-uniform, e3-mongo, e3-pg at 100k, `repeats=4` | E1 MongoDB with `wow.access` held back | E1 p99 1.096 (0.971–1.405), within noise, on an EPYC 9V74. e3-mongo 1.176 and e3-pg 2.665, both within noise. The e3-pg job reached its time limit |
| [43000411141](https://github.com/Ahoo-Wang/Wow/actions/runs/43000411141) | `a7c372ad3e` | `b435768e49` | e3-mongo, e3-pg at 1k and 100k | E3 past the knee: 429 against 504 | See "E3 past the knee" |
| [43013648520](https://github.com/Ahoo-Wang/Wow/actions/runs/43013648520) | `e1fa5a04f1` (#6023, #6025) | beta.1 | component-mongo, component-pg at 100k | Are the walk's statements gone, and the upserts back? | Neither: the walk's four statements at 0.0063–0.0065 (E1) and 0.0151–0.0156 (E3) per operation |
| [43013655859](https://github.com/Ahoo-Wang/Wow/actions/runs/43013655859) | `e1fa5a04f1` | beta.1 | e1-mongo-uniform at 100k, ABAB form | E1 MongoDB on main | p99 1.435 (1.215–1.977), higher in all four pairs, p50 1.231, CPU 1.012 |
| [43037691309](https://github.com/Ahoo-Wang/Wow/actions/runs/43037691309) | `bc9afce234` (main) | `20e32fb9e8` (main with #5420 undone) | e1-pg-uniform, e3-pg at 100k | Check 2: what #5420 costs or buys | See "Snapshot writes on PostgreSQL" |
| [43038596676](https://github.com/Ahoo-Wang/Wow/actions/runs/43038596676) | `f85650f560`, A/A, the second arm with `wow.access.capacity=1000000` | none | e1-mongo-uniform at 100k, `repeats=4` | RT-9: the access follower's read of the stream before | The capacity arm is not lower: p99 1.253 (0.951–2.056), finds 0.0023 on both arms. RT-9 is ruled out |
| [43067146468](https://github.com/Ahoo-Wang/Wow/actions/runs/43067146468) | `4e20d0c22d` (#5394) | `d781d71441`, its parent | e1-mongo-uniform at 100k, ABAB form | The log's two access indexes on MongoDB | Not confirmed (see below). Its base ran the access walk. The job reached its 150-minute limit after two equal-rate pairs |
| [43069705154](https://github.com/Ahoo-Wang/Wow/actions/runs/43069705154) | `16a04c9e3e` (#6041) | beta.1 | component-mongo, component-pg at 100k | X-119: is the walk gone? | Yes: its four statements at 0 |
| [43069714076](https://github.com/Ahoo-Wang/Wow/actions/runs/43069714076) | `16a04c9e3e` | beta.1 | e1-mongo-uniform at 100k, ABAB form | E1 MongoDB after #6041 | p99 1.195 and p50 1.407, both within noise. The p50 is higher in all four pairs |
| [43109405928](https://github.com/Ahoo-Wang/Wow/actions/runs/43109405928), [43109413562](https://github.com/Ahoo-Wang/Wow/actions/runs/43109413562) | `d781d71441`, #5394's parent, `wow.access.walk-rate=1`, `walk-batch=1` | `4e20d0c22d` (#5394) | e1-mongo-uniform at 100k, `repeats=2` each | The log's two access indexes, without the parent's walk | Not confirmed: #5394/parent p99 0.983 and CPU 0.974 (medians of four pairs), within noise (see below) |

The reports of these runs are under [`actions-snapshot/followups/`](actions-snapshot/followups/).

### E1 on MongoDB at 100k accounts

At an equal rate, head over base:

| Run | Head | Runner | p99 | p50 | CPU ms/op | Pairs, p99 base → head (ms) |
| --- | --- | --- | --- | --- | --- | --- |
| L3 42973046096 | `a7c372ad3e` | EPYC 9V45 | 1.490 (1.128–1.853, n=2), higher | 2.011 | 1.020 | two pairs |
| 42987505636 | `44eafe932d` | EPYC 7763 | 1.611 (1.129–2.662, n=4), higher | 1.537 | 1.014 | 374.8 → 745.5, 307.2 → 378.9, 284.7 → 757.8, 430.1 → 485.4 |
| 42998336081 | `669861fb45`, `wow.access` held back | EPYC 9V74 | 1.096 (0.971–1.405, n=4) | 1.416 | 0.989 | 1,572.9 → 876.5, 905.2 → 888.8, 448.5 → 1,237.0, 892.9 → 1,081.3 |
| 43013655859 | `e1fa5a04f1` | EPYC 7763 | 1.435 (1.215–1.977, n=4), higher | 1.231 | 1.012 | 356.4 → 704.5, 368.6 → 598.0, 331.8 → 413.7, 370.7 → 450.6 |
| 43069714076 | `16a04c9e3e` | EPYC 7763 | 1.195 (0.700–1.380, n=4) | 1.407 (1.159–1.497) | 1.020 | four pairs; p50 base 45.8–56.3 ms, head 65.3–68.6 ms |

- **The A/A run 43038596676** (one build, arms with and without the follower's memory raised) gives p99 1.253
  (0.951–2.056) and p50 1.041 (0.846–1.535) between two arms of the same code. That is the spread to read the rows
  against.
- **The store does the same work per command** on both builds. In 43013655859: finds 0.003 against 0.003 per
  operation, updates 1.295 against 1.317. MongoDB is at 86–100% of its one core in every arm, and at 98–100% in the
  runs on an EPYC 7763.
- **The only structural difference** between the tag and main on MongoDB's log comes from #5394 (`4e20d0c22d`). Its
  two indexes, `access_owner` and `access_space`, are partial on every commit document, so each append adds two index
  entries, and nothing in E1 or E3 reads them.
  - A local component run gives the log's writes 10% slower and its indexes 27% larger. That is one run on a shared
    host.
  - PostgreSQL's indexes from the same commit are partial on the key holding a value, so the benchmark's ownerless
    accounts add nothing to them.
- **The run of #5394 against its parent** (43067146468) doesn't confirm it:
  - At the equal rate of 1,112.9/s, two pairs: p99 1.373 (0.865–1.882), with the pairs pointing opposite ways; p50
    1.050; CPU per operation 1.009 (1.001–1.018).
  - Its saturation of 0.858 is the first artifact (the head stopped at 1,024 clients). At 256 clients the arms were
    0.972.
  - Its base did not settle after priming, because its access walk was running. Every base run waited the full 180 s
    at 0.17–0.40 cores, while every head run was quiet in 3 s.
    - On the parent, the seed's first append creates the log collection. The walk's next claim reads heads the seed
      is raising, and walks every account.
    - #5394 creates the collection at start (`events.prepare`), so its claim reads zero heads and completes with
      nothing to stamp.
    - MongoDB's queries per operation in the measured phase agree: the base 0.017–0.041, the head 0.0032–0.0035.
    - So its pairs compared the indexes plus one arm's walk.
- **The A/B again, with the parent's walk held back** (43109405928 and 43109413562, two pairs each):
  - **Setup:** the parent (`d781d71441`) ran with `wow.access.walk-rate=1` and `walk-batch=1`. The workflow's
    `properties` reach only the head arm, so the parent is in the head slot and #5394 in the base slot. The ratios
    below are #5394/parent, the inverses of the runs' own.
  - **Where:** both runs were on EPYC 9V45 runners. Their equal rates were 2,096.0/s and 2,085.2/s.
  - **The walk is out of the comparison.** MongoDB's queries per operation were 0.0038–0.0040 on the parent against
    0.0018–0.0019 on #5394, the parent's walk at one aggregate a second. Commands were 0.010–0.011 on both, and every
    settle was quiet in 3 s.

  | Run, pair | p50 | p99 | CPU ms/op | Alloc/op | p99 ms, #5394 / parent |
  | --- | ---: | ---: | ---: | ---: | --- |
  | 43109405928 e1 | 1.166 | 0.978 | 0.981 | 0.996 | 370.7 / 378.9 |
  | 43109405928 e2 | 0.970 | 0.821 | 0.967 | 0.997 | 395.3 / 481.3 |
  | 43109413562 e1 | 2.155 | 1.855 | 1.012 | 0.971 | 835.6 / 450.6 |
  | 43109413562 e2 | 0.922 | 0.987 | 0.896 | 1.004 | 305.2 / 309.2 |
  | Median | 1.068 | 0.983 | 0.974 | 0.997 | |

  - **Significance:** the runs' own tables (parent/#5394) give p50 0.959 and 0.597 and p99 1.123 and 0.666, all
    within noise.
  - **Saturation:** the calibrated throughput is 0.890 and 0.874 (parent/#5394), "lower". This is the first artifact:
    the parent's searches stopped at 1,024 and 512 clients.
  - **Reading:** on these runners #5394's two indexes show no cost. CPU per operation is below the parent's in three
    of four pairs, and the one pair above 1 in latency is a run's first equal-rate pair.
- **What is left:** the head's p99 at an equal rate stands above the tag's in every run on an EPYC 7763. On #6041's head
  it is within the suite's noise, but its p50 is above in all four pairs. The run without the parent's walk doesn't
  confirm #5394 as its cause. That run was on EPYC 9V45 runners at about twice the rate, so the question is open on
  the 7763. Whether the difference matters at GA is not settled by these runs.
- **Measured locally since, below the knee** ([`m5max-snapshot` at `a7c372ad3e`](https://github.com/Ahoo-Wang/Wow/blob/d9b0781205aa5749bf7e3941dfcbb622c2eaac36/test/benchmarks/results/reports/m5max-snapshot.md),
  "Addendum: MongoDB's cost per commit below the knee"):
  - The two indexes cost the log's insert time on every comparison, 9–15% per commit. On the tag's documents they
    also cost 7–10% of mongod's CPU per commit.
  - Main's documents and code without them cost another 4–8%.
  - The decision is D420: the indexes leave out the nulls, as PostgreSQL's do. Re-measured with the same instrument
    ([m5max-snapshot](m5max-snapshot.md), "MongoDB's cost per commit below the knee (D420)"), D420's log insert time
    per commit is 1–3% over the tag's and its mongod CPU 3–5% under, within the tag's own spread.

### Snapshot writes on PostgreSQL

Component suites at 100k (statements per operation, head against the tag):

| | Tag (43013648520) | Head before #6041 (`e1fa5a04f1`, 43013648520) | #6041 (`16a04c9e3e`) against the tag (43069705154) |
| --- | ---: | ---: | ---: |
| E1: the walk's four statements, each | 0 (the snapshot table's select 0.0001) | 0.0063–0.0065 | 0 (the snapshot table's select 0.0001) |
| E3: the walk's four statements, each | 0 (the snapshot table's select 0.0002) | 0.0151–0.0156 | 0 (the snapshot table's select 0.0003) |
| E1: snapshot upserts | 0.2013 | 0.2483 (+23%) | 0.2377 against 0.2140 (+11%) |
| E3: snapshot upserts | 0.5808 | 0.6948 (+20%) | 0.6642 against 0.5862 (+13%) |

- **The walk's four statements** are the select and update of the log, the select of the snapshot table and the
  insert of the snapshot. They are one per walked aggregate, at the walk's pace through the measured phase. #6041
  records the walk as done when the store is created, and on its head they are at 0. MongoDB's finds fall the same way:
  E1 0.0083 → 0.0011 (tag 0.0016).
- **The remaining upserts are #5420's.**
  - `SnapshotWrites` writes whatever arrived while its previous write ran. Since #5420, a covered context's snapshot
    write commits with `synchronous_commit = off`, so each write ends sooner and fewer saves gather behind it.
  - Same rows, in more and smaller statements. A local count with the flush restored gave back the tag's 8.5 rows per
    statement.
- **What #5420 costs or buys (Check 2, 43037691309, EPYC 7763).** Head is main, base is main with #5420 undone.
  Medians of the equal-rate rounds:

  | Per operation | E1 base → head | E3 base → head |
  | --- | --- | --- |
  | WAL syncs | 0.399 → 0.220 | 1.693 → 0.934 (−45%) |
  | WAL sync time | 0.193 → 0.140 ms | 0.808 → 0.603 ms |
  | Commits | 0.505 → 0.563 | 2.148 → 2.358 (+10%) |
  | PostgreSQL CPU | 0.466 → 0.468 ms | 1.667 → 1.595 ms |
  | p99 at an equal rate | 1.067 (0.660–1.810), within ±22.7% | 0.984 (0.841–1.755), within ±39.5% |
  | Saturation | 1.104 | 1.351 |

  The smaller snapshot batches are the price of half the WAL flushes: about 10% more commits, at the same PostgreSQL
  CPU and no worse latency or saturation. The performance page states this when its figures go in.

### E3 past the knee

At 2,048 and 4,096 clients in the closed-loop searches of 43000411141, `b435768e49` against `a7c372ad3e`:

| Suite (runner) | Arm | c2048 | c4096 |
| --- | --- | --- | --- |
| E3 MongoDB 1k (EPYC 9V45) | `b435768e49` | 790.4/s, p99 5,898 ms, no failures | 468.1/s, p99 10,289 ms, 8.0% failed: 318 × 504, 9 × 429 |
| E3 MongoDB 1k (EPYC 9V45) | head | 860.1/s, p99 5,407 ms, no failures | 497.4/s, p99 10,224 ms, 17.3% failed: 833 × 504, no 429 |
| E3 PostgreSQL 1k (EPYC 9V45) | `b435768e49` | 928.0/s, p99 4,850 ms, no failures | 560.9/s, p99 9,634 ms, 2.2% failed: 100 × 504 |
| E3 PostgreSQL 1k (EPYC 9V45) | head | 976.0/s, p99 5,079 ms, 0.7% failed: 52 × 504 | not reached: its search stopped at 2,048 |
| E3 MongoDB 100k (EPYC 9V74) | `b435768e49` | 727.5/s, p99 3,981 ms, no failures | 467.2/s, p99 8,258 ms, 59.4% failed: 5,456 × 429, 21 × 504 |
| E3 MongoDB 100k (EPYC 9V74) | head | not reached: its search stopped at 4 clients | not reached |
| E3 PostgreSQL 100k (EPYC 9V45) | both | not reached: both searches stopped at 256 clients | not reached |

- **At 1k both commits fail by waits that ran out.** The head's 17.3% against 8.0% on MongoDB at 4,096 is one step of
  8 s each, and no cause is stated.
- **At 100k on MongoDB, `b435768e49` refuses at admission** (429 `wow.command.busy`), as it did on the M5 Max. The head
  has no step there, because no input forces the search past its stop. This run is the case for a harness suite that
  sweeps E3 past the knee with its steps forced (X-106).
- **In L3, against `v10.0.0-beta.1` itself,** no step of either arm failed by 429. E3 MongoDB 1k at 4,096: tag 7.2%
  failed (294 × 504), head 22.1% (1,027 × 504). L4 has no tag arm, and its E3 cliff stops at 500/s with every step
  kept up.
- **X-118:** on the M5 Max, the change from refusing to timing out was found at #5533 (`04502ad20e`), and #6070 closes
  it. These Actions runs measure neither.

## L4: sustainable rates

The highest open-loop rate with at most 1% errors and p99 within 200 ms, found by bisection. One node, 100k accounts,
primed.

| Store | Scenario | Sustainable | p50 | p99 | CPU ms/op | Saturation | Share | alpha.5 (40244742539) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MongoDB | E1 | **1,354/s** | 19.1 ms | 171.0 ms | 2.07 | 2,166/s | 63% | 1,152/s, 2.37 ms/op, 56% |
| MongoDB | E2 | **677/s** | 29.2 ms | 196.6 ms | 4.02 | 1,353/s | 50% | 547/s, 4.74 ms/op, 50% |
| MongoDB | E3 | **486/s** | 26.6 ms | 161.8 ms | 5.75 | 777/s | 63% | 363/s, 7.54 ms/op, 41% |
| PostgreSQL | E1 | **2,610/s** | 4.6 ms | 160.8 ms | 0.97 | 4,913/s | 53% | 1,165/s, 2.03 ms/op, 47% |
| PostgreSQL | E3 | **742/s** | 3.0 ms | 188.4 ms | 3.23 | 2,374/s | 31% | 751/s, 3.53 ms/op, 47% |

- **MongoDB ran on an EPYC 7763 both times:** 1.18× (E1), 1.24× (E2) and 1.34× (E3) alpha.5's rate, at 13–24% less
  CPU per operation. Each is one bisection against one.
- **PostgreSQL moved from an EPYC 7763 to a 9V45,** so neither its 2.24× on E1 nor its level E3 is claimed.
- The brackets are 1,354–1,422/s, 677–719/s and 486–510/s on MongoDB, and 2,610–2,763/s and 742–816/s on PostgreSQL.

## L4: cliff

E3 in fixed open-loop steps, a minute each on fresh containers.

| Rate | PostgreSQL p50 / p99 | MongoDB p50 / p99 | alpha.5 PostgreSQL p50 / p99 | alpha.5 MongoDB p50 / p99 |
| --- | --- | --- | --- | --- |
| 50/s | 2.8 / 13.3 ms | 4.7 / 64.0 ms | 3.5 / 9.5 ms | 4.5 / 17.2 ms |
| 100/s | 2.3 / 21.1 ms | 4.5 / 249.9 ms | 3.6 / 12.3 ms | 5.2 / 34.6 ms |
| 200/s | 2.0 / 56.6 ms | 3.6 / 90.1 ms | 2.8 / 38.4 ms | 4.7 / 60.4 ms |
| 300/s | 2.0 / 74.2 ms | 4.3 / 97.8 ms | 2.8 / 27.3 ms | 4.4 / 110 ms |
| 400/s | 2.2 / 103.9 ms | 7.1 / 182.3 ms | 4.1 / 59.9 ms | 9.3 / 167 ms |
| 500/s | 3.2 / 137.2 ms | 9.2 / 148.5 ms | 7.1 / 64.5 ms | 9.9 / 182 ms |

- **Every step kept up on both stores,** with no failure, no command refused as busy and no tail falling back to the
  store.
- **Runners:** PostgreSQL ran on an EPYC 9V74 (alpha.5: 7763) and MongoDB on a 9V45 both times.
- **PostgreSQL's p99 is higher from 200/s on** (56.6–137.2 ms against 27.3–64.5 ms), and its p50 lower, on another CPU
  model, so neither is claimed.
- **MongoDB's p99 at 100/s is 249.9 ms** against p50 4.5 ms. That is one minute at 100 operations a second.

## L4: cluster and hand-over (E5)

Three nodes, requests round-robin, 90 s measured. In the hand-over runs node 2 is stopped 25 s into the measured phase
and restarted 20 s later. Both stores ran on an EPYC 7763. In alpha.5's run, MongoDB was on a 7763 and PostgreSQL on a
9V45.

| Store | Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Lowest second | alpha.5: p99, worst second, failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | E1 steady | 150/s | 150/s | 0 | 3.1 ms | 13.9 ms | 54.5 ms | 150/s | 12.7 ms, 117 ms, 0 |
| PostgreSQL | E1 hand-over | 150/s | 150/s | 0 | 4.8 ms | 487.4 ms | 1,786 ms | 150/s | 71.7 ms, 758 ms, 0 |
| PostgreSQL | E3 steady | 50/s | 50/s | 0 | 6.7 ms | 23.4 ms | 50.9 ms | 50/s | 36.6 ms, 238 ms, 0 |
| PostgreSQL | E3 hand-over | 50/s | 50/s | 0 | 10.6 ms | 1,065 ms | 4,170 ms | 50/s | 90.1 ms, 1,205 ms, 0 |
| MongoDB | E1 steady | 150/s | 150/s | 0 | 4.7 ms | 31.5 ms | 81.4 ms | 150/s | 52.7 ms, 205 ms, 0 |
| MongoDB | E1 hand-over | 150/s | 150/s | 1 | 11.5 ms | 2,359 ms | 5,079 ms | 149/s | 1,401 ms, 4,358 ms, 0 |
| MongoDB | E3 steady | 50/s | 50/s | 0 | 10.5 ms | 49.7 ms | 107.5 ms | 50/s | 147 ms, 449 ms, 0 |
| MongoDB | E3 hand-over | 50/s | 49.7/s | 30 | 22.4 ms | 6,390 ms | 9,713 ms | 44/s | 3,375 ms, 6,716 ms, 4 |

- **The steady runs carried the full rate** with no failure. Three-node MongoDB E3 steady p99 is 49.7 ms: the 147 ms
  the last report recorded is not there again (below).
- **The hand-overs carried the full rate on PostgreSQL** with no failure. Their worst seconds, 1.8 and 4.2 s, are
  higher than alpha.5's, on another CPU model, so they are not claimed.
- **MongoDB's E3 hand-over failed 30 of 4,500:**
  - 29 × `504 wow.wait.timeout` on committed commands, and 1 × `503 wow.node.closing`, against 4 at alpha.5 on the
    same CPU model.
  - Its worst second's p99 is 9.7 s against 6.7 s, and its lowest second 44/s.
  - The nodes logged `forwardFailed local` 27 times and tail reads after a gap 22 times.
  - It is one hand-over against one, and no cause is stated.
- **MongoDB's E1 hand-over** failed 1 (an `IOException`), with p99 2.4 s against 1.4 s.

## L4: cold cache, a million accounts

| Store | Scenario | Rate | p50 | p95 | p99 | Worst-second p99 | CPU ms/op | Alloc/op | alpha.5: p50 / p99, CPU ms/op, alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | E1 | 200/s | 1.9 ms | 4.5 ms | 6.8 ms | 19.1 ms | 6.03 | 137 KB | 1.4 / 16.8 ms, 3.98, 113 KB |
| PostgreSQL | E4 point read | 500/s | 0.6 ms | 2.2 ms | 4.0 ms | 9.2 ms | 1.69 | 36 KB | 0.4 / 2.5 ms, 1.01, 33 KB |
| MongoDB | E1 | 200/s | 4.3 ms | 20.0 ms | 43.3 ms | 101.9 ms | 9.89 | 290 KB | 3.6 / 68.1 ms, 9.36, 234 KB |
| MongoDB | E4 point read | 500/s | 0.9 ms | 3.5 ms | 6.7 ms | 45.3 ms | 2.57 | 77 KB | 0.9 / 6.2 ms, 2.44, 71 KB |

- **Runners:** both stores ran on an EPYC 7763. In alpha.5's run, MongoDB was on a 7763 and PostgreSQL on a 9V45.
- **Cold PostgreSQL E1 p99 is 6.8 ms,** against 16.8 ms at alpha.5 and 6.4 ms at alpha.4 (below). Its CPU per
  operation, 6.03 against 3.98 ms, crosses CPU models and is not claimed.
- **On MongoDB, on the same CPU model:**
  - E1's p99 is lower (68.1 → 43.3 ms) and its p50 higher (3.6 → 4.3 ms).
  - Allocation per operation is 24% higher (234 → 290 KB) and CPU 6% higher.
  - Each is one 40 s run of 8,000 operations against one, and no cause is stated.

## The two comparisons the last report left open

| Comparison | Now | alpha.5 | alpha.4 | Runner (now; alpha.5; alpha.4) |
| --- | --- | --- | --- | --- |
| Cold-cache PostgreSQL E1 p99 | 6.8 ms (p50 1.9 ms, worst second 19.1 ms) | 16.8 ms | 6.4 ms | EPYC 7763; 9V45; 9V45 |
| Three-node MongoDB E3 steady p99 | 49.7 ms (p50 10.5 ms, worst second 107.5 ms) | 147 ms | 60.7 ms | EPYC 7763 in all three |

Neither is there again. L4 gives single runs, with no A/A or ABAB for either, so each is closed by its absence, not
measured. A `cold-pg` or `e5-mongo` pair with `aa=true` would measure them.

## Gaps of the tooling (X-106)

- **No input forces a closed-loop search past its stop.** `bench.mjs`'s search doubles the concurrency up to 4,096,
  but ends after two steps that gain under 5%. No `bench.yml` input or suite overrides that. It is why 26 of L3's 40
  searches stopped early, and why the head has no step at 2,048 or 4,096 clients on MongoDB at 100k in 43000411141.
  A suite that sweeps E3 past the knee with its steps forced, as the sessions suites do, would answer the knee on a
  runner. `bench.mjs` cannot grow before it is split, which is its own piece of work.
- **The calibrated rounds' verdicts follow each search's share.** They compare the rates the two searches chose.
- **A significance ratio can lie outside its own interval** (E1 PostgreSQL at 100k in L3; the index run's p99).
- **An ABAB of e1-mongo-uniform at 100k with `repeats=4` and `profile=true` reaches the job's 150-minute limit.**
  42987505636 reached it after its timed rounds, 43067146468 before its last pairs, and 43069714076 ended at 148.5
  minutes. A node whose access walk runs after priming (#5394's parent) adds up to 3 minutes a run. Two dispatches with
  `repeats=2` each took about an hour.
- **`properties` reaches only the head arm.** A change to the base arm's configuration means putting the base's ref in
  the head slot and reading the runs' ratios inverted, as 43109405928 and 43109413562 do.
- **A base at or after `84f9e281dc` did not install:** the server archive also holds the search server, so
  `INSTALL_BASE` found two directories. #6053 fixes it.

## Caveats

- **One shared 4-core runner per job.** The server, the store, Kafka and the load generator compete for the same cores,
  so absolute rates are far below a dedicated deployment's. Production sizing needs a run on the target hardware.
- **The processor differs between jobs.** Only a pair on one runner gives the code's ratio. The L4 comparisons with
  alpha.5 name the CPU model of both sides, and where it changed no change is claimed.
- **The 1k suites have no noise threshold.** Their latency ratios are two pairs each at a third of saturation or less,
  where two runs of the same code are known to differ widely.
- **The harness is the head's for both arms,** so L3's ratios are of the server code. The L4 comparisons with alpha.5's
  run also include the harness changes since.

## Data

[`actions-snapshot/`](actions-snapshot/) holds the raw results:

- each tier's report, `l3/report.md` and `l4/report.md`, with every round, probe and anomaly;
- per suite, `summary.md` and `suite.json`;
- per round or probe, `result.json`: latencies, per-second series, histogram, resources and environment, commit
  included;
- per closed-loop search, `saturation.json`;
- the follow-up runs' reports, `followups/<run>/report.md`.

The node, seed, prime and settle logs, the flight recordings and the server distributions stay in the runs' artifacts.

## Reproduce

```bash
gh workflow run bench.yml --ref main -f head_ref=main -f tier=L3
```

```bash
gh workflow run bench.yml --ref main -f head_ref=main -f tier=L4
```

```bash
gh workflow run bench.yml --ref main -f head_ref=main -f suites=e1-mongo-uniform -f dataset=100k -f repeats=4 -f profile=true
```

Without `base_ref`, L3's baseline is the newest `v10.*` tag with the benchmark server. The workflow keeps one pending
run per ref, so a second run is dispatched from another branch. Each job uploads `bench-result-<suite>`; the Report job
uploads `e2e-bench-report`.

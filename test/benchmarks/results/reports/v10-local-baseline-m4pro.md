# V10 local baseline on Apple M4 Pro

The local baseline's stages on a second machine, without JFR, for its ceilings and its costs per operation, and the state cache's effect, measured as 1 GB against 2 GB of heap. Run on 2026-10-24; the results and scripts are in [`v10-local-baseline-m4pro/`](v10-local-baseline-m4pro/) (`SUMMARY.md` tabulates every run, `cost-per-op.tsv` the costs).

## Setup

| | |
| --- | --- |
| Commit | `cb9ad038d` (`main`; the script committed its results as it went, so the runs report the tree dirty) |
| Machine | MacBook Pro, Apple M4 Pro (10 performance and 4 efficiency cores), 24 GB, macOS 27.0.1 |
| Docker | Desktop 4.96.3, engine 29.9.2, 12 CPUs and 15.6 GiB, `Docker.raw` on VirtioFS |
| JDK | Temurin 21.0.13 for the node and the load generator |
| Topology | One node, 1 GB heap (stage 2: 1 GB and 2 GB), G1; PostgreSQL or MongoDB and Kafka in containers |
| Data | 100,000 accounts, primed once per session; uniform keys unless noted |

**The disk sets the tails.** Every PostgreSQL run's longest WAL flush took 0.7–4.9 s and MongoDB's checkpoints up to 5.3 s; a stall that long fills the node's commands in flight (4,096), and the commands beyond are refused as `wow.command.busy`, which is where the failures in the tables come from. The p99s here are the disk's, not the node's: read the costs per operation, the counts and the ceilings, not the tails.

## Stage 0: one rate, three sessions

PostgreSQL E1 at 6,346/s (half of a first sweep's peak), two 60-second runs in each of three sessions:

| Session, run | Failed | p50 | p99 | Node CPU per op | Allocated per op | Rows fetched per op | Longest WAL flush |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1, 1 | 0 | 22.3 ms | 285 ms | 0.448 ms | 100.3 KB | 19.3 | 898 ms |
| 1, 2 | 325 | 43.8 ms | 664 ms | 0.400 ms | 104.1 KB | 23.5 | 987 ms |
| 2, 1 | 0 | 4.0 ms | 77 ms | 0.511 ms | 81.3 KB | 3.0 | 961 ms |
| 2, 2 | 0 | 7.6 ms | 319 ms | 0.460 ms | 84.2 KB | 3.8 | 898 ms |
| 3, 1 | 637 | 4.1 ms | 248 ms | 0.469 ms | 81.6 KB | 3.0 | 1,190 ms |
| 3, 2 | 0 | 2.9 ms | 41 ms | 0.515 ms | 83.6 KB | 4.0 | 1,032 ms |

- Sessions 2 and 3 agree within a few percent: 0.46–0.52 ms of node CPU and 81–84 KB per command, 3–4 rows read.
- Session 1 ran the sweep that set the rate first, and then read six times the rows and allocated a fifth more per command. The sweep's traffic left the state cache holding fewer accounts (stage 2), so the same rate loaded more of them.

## Stage 1: ceilings and costs

Closed-loop sweeps, 30 s a step, then fixed rates at 25%, 50% and 75% of each peak, in one session per backend:

| Peak | PostgreSQL | MongoDB |
| --- | --- | --- |
| E1, uniform | ≥ 11,233/s (at 1,024, the top step) | 8,856/s (at 256) |
| E1, Zipf | 4,512/s (at 32) | 3,469/s (at 256) |
| E3, uniform | ≥ 1,707/s (at 512, the top step) | 1,823/s (at 256) |

At 50% of each peak, per operation:

| | Node CPU | Allocated | Store CPU | Kafka CPU | Store work |
| --- | --- | --- | --- | --- | --- |
| PostgreSQL E1, uniform | 0.418 ms | 98.5 KB | 0.370 ms | 0.080 ms | 1.01 commits, 16.3 rows |
| PostgreSQL E1, Zipf | 0.529 ms | 91.9 KB | 0.258 ms | 0.143 ms | 1.73 commits, 5.2 rows |
| PostgreSQL E3 | 1.64 ms | 290 KB | 1.99 ms | 0.527 ms | 5.54 commits, 93 rows |
| MongoDB E1, uniform | 0.527 ms | 153 KB | 1.12 ms | 0.099 ms | 0.375 queries |
| MongoDB E1, Zipf | 0.642 ms | 142 KB | 0.699 ms | 0.193 ms | 0.045 queries |
| MongoDB E3 | 1.76 ms | 487 KB | 5.40 ms | 0.466 ms | 1.16 queries |

- Each fixed rate ran after its scenario's sweep, so the cache was already short of accounts (stage 2): PostgreSQL E1 read 16 rows a command here against 3–4 in stage 0's sessions 2 and 3.
- With Zipf keys the ceiling is the hottest accounts' lanes, one append per command; this commit predates the batching of D273, which lifts it ([report](v10-hot-aggregate-batching.md)). The lanes also show in the cost: more commits per command (1.73 against 1.01) and twice the Kafka CPU, smaller groups.

## Stage 2: 1 GB against 2 GB of heap

Four sessions per backend in the order 1 GB, 2 GB, 1 GB, 2 GB: an E1 sweep to 1,024 and an E3 sweep to 512, then E1 and E3 at fixed rates set from the first 1 GB session's peaks.

| | PostgreSQL round 1 | PostgreSQL round 2 | MongoDB round 1 | MongoDB round 2 |
| --- | --- | --- | --- | --- |
| E1 peak, 1 GB → 2 GB | 12,184 → 18,591/s (1.53×) | 12,456 → 19,113/s (1.53×) | 9,458 → 13,923/s (1.47×) | 10,460 → 14,171/s (1.35×) |
| E3 peak, 1 GB → 2 GB | 2,855 → 4,391/s (1.54×) | 2,698 → 4,731/s (1.75×) | 2,526 → 4,270/s (1.69×) | 2,429 → 4,344/s (1.79×) |
| E1 reads per op | 31.4 → 4.8 rows | 30.9 → 4.5 rows | 1.08 → 0.01 queries | 0.76 → 0.02 queries |
| E3 reads per op | 75.4 → 19.3 rows | 75.5 → 17.5 rows | 1.05 → 0.07 queries | 1.06 → 0.09 queries |
| E1 allocated per op | 110 → 86 KB | 109 → 86 KB | 175 → 112 KB | 168 → 113 KB |
| E3 allocated per op | 268 → 215 KB | 264 → 213 KB | 446 → 309 KB | 447 → 326 KB |

- Twice the heap lifts every ceiling 1.35–1.79 times, in both rounds and on both backends: at 1 GB, after the sweeps, nearly every command loaded its aggregate (MongoDB about one query a command), at 2 GB nearly none did.
- The cause is the state cache's budget, a tenth of the heap in estimated bytes. Each cached state's recent-request rings grow with the requests its aggregate committed (decision D261), and each state was weighed at a fixed 1 KB besides; after enough traffic the 100,000 accounts no longer fit. On the M4, a heap histogram counted 95,786 cached states before an E1 sweep and 66,005 after it; the fixed estimate was also about twice what such a state takes. Roadmap §6 item 13 follows it up.
- The fixed rates' latencies moved both ways with the disk (p99 from 0.14 to 8.8 times); their throughput was the same at both heaps.

## L1: the kernel and one store

The kernel and one store in one JVM, 100,000 accounts:

| Backend, scenario | Concurrency 16 | 64 | 256 |
| --- | --- | --- | --- |
| PostgreSQL E1 | 5,648/s, 2.62 round trips, 78.1 KB | 6,742/s, 2.06, 76.8 KB | 8,710/s, 1.37, 76.3 KB |
| PostgreSQL E3 | 2,572/s, 5.29, 195 KB | 2,944/s, 3.99, 199 KB | 4,758/s, 1.97, 200 KB |
| MongoDB E1 | 4,648/s, 3.05, 174 KB | 7,039/s, 2.76, 166 KB | 10,716/s, 2.02, 144 KB |
| MongoDB E3 | 2,309/s, 5.32, 360 KB | 3,630/s, 4.48, 335 KB | 6,307/s, 2.55, 268 KB |

The round trips per operation fall as the concurrency rises, the group commits taking more streams each; the bytes per operation hardly move.

## Against the M4 baseline

The [M4 baseline](v10-local-baseline.md) ran `c58787552` with JFR on a 10-core M4 with 10 Docker CPUs and 7.9 GB. Since then, the code changed (#4850, #4851, #4858, #4859, #4861: the tail pipeline and snapshot writes as BSON, the latest stream's read, the state cache by bytes, the last sequences), and so did the machine, Docker's share and the recording. No difference between the two is one cause's; the numbers side by side:

| | M4 baseline | M4 Pro |
| --- | --- | --- |
| PostgreSQL E1 peak | 3,811/s (with JFR; 5,084/s without) | ≥ 11,233/s |
| PostgreSQL E3 peak | 1,660/s | ≥ 1,707/s |
| MongoDB E1 peak | 3,446/s | 8,856/s |
| MongoDB E3 peak | 1,427/s | 1,823/s |
| L1 PostgreSQL E1 at 16 | 4,000/s, 2.79 round trips | 5,648/s, 2.62 |
| L1 MongoDB E1 at 16 | 2,038/s, 224 KB | 4,648/s, 174 KB |

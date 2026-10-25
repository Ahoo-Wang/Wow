# V10 milestone snapshot on Apple M4 Pro

The milestone drift guard of roadmap §6 item 19: the local baseline's stages on the M4 Pro, after D273 (hot-aggregate batching), D274 (the state cache weighed by measured sizes) and D275 (cross-partition group commit), with the production-like store settings of #4869. Run on 2026-10-24 and 25 at `ed04b1728` (#4870's head; its later commits change tests only). Its capacity numbers are a snapshot, not a decision: changes are decided by an ABAB against `main`. The runs and scripts are in [`v10-m4pro-snapshot/`](v10-m4pro-snapshot/) (`SUMMARY.md` and `COMPARE.md` tabulate every run).

## Setup

| | |
| --- | --- |
| Commit | `ed04b1728` (`claude/cross-partition-group-commit`, merged as #4870) |
| Machine | MacBook Pro, Apple M4 Pro (10 performance and 4 efficiency cores), 24 GB |
| Docker | Desktop 4.96.3, engine 29.9.2, 12 CPUs and 15.6 GiB, `Docker.raw` on VirtioFS, the stores on disk (no `--tmpfs`) |
| Stores | PostgreSQL `max_wal_size=8GB`, `checkpoint_timeout=15min`, `wal_compression=lz4`, `shared_buffers=512MB`; MongoDB's cache 2 GB |
| Node | One, 1 GB heap unless noted, G1, Temurin 21.0.13 |
| Data | 100,000 accounts, primed once per session; uniform keys unless noted |

## Ceilings

Closed-loop sweeps, 30 s a step, concurrency 1, 4, 16, 64, 256, 1,024 (`--growth=4`), in one session per backend. Against the [previous M4 Pro run](v10-local-baseline-m4pro.md) (`cb9ad038d`, 2026-10-24, the stores' default settings, before D273–D275):

| Peak, per second | PostgreSQL | MongoDB | PostgreSQL before | MongoDB before |
| --- | --- | --- | --- | --- |
| E1, uniform | ≥ 21,204 (top step) | ≥ 15,667 (top step) | ≥ 11,233 | 8,856 |
| E1, Zipf | 9,352 (256) | 13,273 (256) | 4,512 | 3,469 |
| E1, one hot account | 10,260 (256) | 10,210 (256) | — | — |
| E3, uniform | ≥ 4,390 (top step) | ≥ 3,169 (top step) | ≥ 1,707 | 1,823 |

- Every uniform peak is at the sweep's top step, so they are lower bounds. No sweep failed a command.
- The Zipf and hot-account ceilings are where one aggregate's lane batches (D273): 2–3.8 times the previous run's Zipf.
- The code, the store settings and the sweep's steps all changed since; no ratio is one cause's.

## Repeatability

PostgreSQL E1 at 10,602/s (half the uniform peak) in two fresh sessions:

| Session | Throughput | Failed | p50 | p99 | Node CPU per op | Allocated per op |
| --- | --- | --- | --- | --- | --- | --- |
| 2 | 10,602/s | 0 | 28.7 ms | 122 ms | 0.299 ms | 85.0 KB |
| 3 | 10,602/s | 0 | 27.5 ms | 127 ms | 0.293 ms | 85.1 KB |

Two fresh sessions agree within 2% on the costs per operation and 4% on the latencies.

## 1 GB against 2 GB of heap

An E1 sweep, then E1 at a fixed rate (8,064/s on PostgreSQL, 7,502/s on MongoDB), in fresh sessions:

| | PostgreSQL 1 GB | PostgreSQL 2 GB | MongoDB 1 GB | MongoDB 2 GB |
| --- | --- | --- | --- | --- |
| E1 peak | ≥ 16,127/s | ≥ 19,536/s | ≥ 15,004/s | ≥ 14,679/s |
| Fixed rate: throughput, failed | 7,995/s, 4,148 | 7,189/s, 52,490 | 7,400/s, 6,124 | 7,502/s, 0 |
| p50 / p99 | 17.8 / 594 ms | 20.4 / 1,925 ms | 10.1 / 512 ms | 10.9 / 225 ms |
| Node CPU, allocated per op | 0.346 ms, 83.7 KB | 0.349 ms, 88.9 KB | 0.279 ms, 91.0 KB | 0.272 ms, 91.0 KB |
| Rows fetched / queries per op | 2.5 rows | 2.5 rows | 0.001 queries | 0.001 queries |

- The heap no longer sets the ceiling after one sweep: the costs and reads per command are the same at both heaps. The previous run, before D274, had 1 GB miss the cache after a sweep and 2 GB lift the peaks 1.35–1.79 times.
- The fixed rates' failures and tails are the disk's: the longest WAL flush was 908 ms at 1 GB and 2,877 ms at 2 GB, MongoDB's longest checkpoint 871 and 773 ms. A stall that long fills the node's 4,096 commands in flight, and the commands beyond are refused as `wow.command.busy`.

## L1: the kernel and one store

The kernel and one store in one JVM, 100,000 accounts, against the previous run:

| | Concurrency 16 | 64 | 256 | 16 before | 256 before |
| --- | --- | --- | --- | --- | --- |
| PostgreSQL E1 | 7,911/s, 1.46 round trips, 58 KB | 9,082/s, 1.05, 60 KB | 14,099/s, 0.42, 61 KB | 5,648/s, 2.62, 78 KB | 8,710/s, 1.37, 76 KB |
| PostgreSQL E3 | 2,860/s, 4.05, 177 KB | 3,265/s, 2.85, 186 KB | 4,906/s, 1.00, 189 KB | 2,572/s, 5.29, 195 KB | 4,758/s, 1.97, 200 KB |
| MongoDB E1 | 5,444/s, 0.64, 79 KB | 12,280/s, 0.20, 66 KB | 20,869/s, 0.07, 61 KB | 4,648/s, 3.05, 174 KB | 10,716/s, 2.02, 144 KB |
| MongoDB E3 | 2,256/s, 1.49, 230 KB | 4,380/s, 0.53, 201 KB | 6,746/s, 0.21, 191 KB | 2,309/s, 5.32, 360 KB | 6,307/s, 2.55, 268 KB |

The round trips per operation fall the most on MongoDB (3.05 to 0.64 at 16, 2.02 to 0.07 at 256): one call takes several partitions' groups (D275), and the state cache answers the reads.

## Found: after the four sweeps, the cache misses and the fixed rates fail

The first session of each backend ran the four sweeps (E1 uniform, Zipf, hot, E3), then E1 and E3 at 25%, 50% and 75% of the peaks, on the same node. There the fixed E1 rates failed where fresh sessions do not:

| E1 after the sweeps | Offered | Throughput | Failed (`wow.command.busy`) | p50 | Reads per op | Disk blocks per op |
| --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL, 25% | 5,301/s | 4,895/s | 24,350 | 27.1 ms | 23.8 rows | 18.9 |
| PostgreSQL, 50% | 10,602/s | 4,906/s | 341,775 | 791 ms | 27.0 rows | 22.0 |
| PostgreSQL, fresh session (above) | 10,602/s | 10,602/s | 0 | 28.7 ms | 2.2 rows | 0.0 |
| MongoDB, 25% | 3,917/s | 3,679/s | 14,305 | 24.6 ms | 0.343 queries | — |
| MongoDB, 50% | 7,833/s | 5,068/s | 165,918 | 692 ms | 0.358 queries | — |
| MongoDB, fresh session (above) | 7,502/s | 7,502/s | 0 | 10.9 ms | 0.001 queries | — |

- The node was quiet before each run (below 0.25 cores for 3 s), so no snapshot or subscription backlog remained.
- About a third of the commands loaded their aggregate (MongoDB's queries per command), and PostgreSQL read its pages from disk; both stayed so over the 30 s warm-up and the 60 s measured, so the cache did not refill under the load.
- The cause is not established yet. The sweeps committed 1.0–1.7 million E1 commands each (about 17 per account in the uniform one, too few to grow an account's recent-request rings past 32 slots) and about 350,000 transfers, each with its saga, in E3. Roadmap §6 item 20 follows it up: reproduce E3's sweep then a fixed E1 locally, and count what the state cache holds.
- A node in production runs for days, not one fresh session: this is closer to its steady state than a fresh session is.

## Reading the runs

- `SUMMARY.md` tabulates every run, `COMPARE.md` the costs per operation by session; the scripts that ran them are in `scripts/`.
- The run's own summary is in Chinese; this report reads its numbers from the runs' `summary.md` and `result.json`.

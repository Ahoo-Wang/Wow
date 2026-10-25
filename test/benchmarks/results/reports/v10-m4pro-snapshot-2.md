# V10 milestone snapshot on Apple M4 Pro, second run

The milestone drift guard of roadmap §6 item 19, run again after D276–D280 (the MongoDB position as `_id`, the partition-led keys of D277, 32 recent requests per cached aggregate, the stores' operation timeouts and PostgreSQL's cross-partition group commit). Run on 2026-10-25 at `081c9f533` (#4881). The machine, Docker, store settings, data and stages are the [first snapshot](v10-m4pro-snapshot.md)'s (`ed04b1728`, 2026-10-24); the runs, scripts and tables are in [`v10-m4pro-snapshot-2/`](v10-m4pro-snapshot-2/) (`SUMMARY.md`, `COMPARE.md`). A snapshot records capacity; changes are decided by an ABAB against `main`.

## Ceilings

Closed-loop sweeps, 30 s a step, concurrency 1 to 1,024 (`--growth=4`), one session per backend:

| Peak, per second | PostgreSQL | First snapshot | MongoDB | First snapshot |
| --- | --- | --- | --- | --- |
| E1, uniform | ≥ 21,005 (top step) | ≥ 21,204 | ≥ 16,215 (top step) | ≥ 15,667 |
| E1, Zipf | 11,951 (256) | 9,352 | ≥ 13,345 (top step) | 13,273 |
| E1, one hot account | 9,054 (256) | 10,260 | 9,779 (256) | 10,210 |
| E3, uniform | **≥ 7,099 (top step)** | ≥ 4,390 | **≥ 5,781 (top step)** | ≥ 3,169 |

- **E3 rose 62% on PostgreSQL and 82% on MongoDB**, both still at the top step, so both are lower bounds. On PostgreSQL, D280 commits a transfer's appends to different partitions together ([its report](v10-pg-cross-partition.md): +57% at the top step on the local M4); MongoDB's gain comes from D276–D279, not separated here.
- The uniform E1 peaks are at the top step in both runs, so they bound the ceiling from below and do not compare.
- PostgreSQL's Zipf rose 28%; the hot account fell 4–12%, within what one session per backend can tell.
- No sweep failed a command.

## The fixed rates after the sweeps (item 20)

The first session of each backend ran the four sweeps, then E1 and E3 at 25%, 50% and 75% of its peaks. In the first snapshot, E1 at 25% failed there because the recent-request rings had pushed accounts out of the state cache (D278 fixed it):

| E1 at 25% after the sweeps | Offered | Failed | p50 | Reads per op |
| --- | --- | --- | --- | --- |
| PostgreSQL, first snapshot | 5,301/s | 24,350 | 27.1 ms | 23.8 rows, 18.9 disk blocks |
| PostgreSQL, now | 5,251/s | 17,992 | 6.1 ms | **4.05 rows, 0.31 disk blocks** |
| MongoDB, first snapshot | 3,917/s | 14,305 | 24.6 ms | 0.343 queries |
| MongoDB, now | 4,054/s | **0** | 4.9 ms | **0.005 queries** |

The cache now holds the accounts after the sweeps: reads per command are back at a fresh session's on both backends, and MongoDB's 25% runs clean. PostgreSQL's remaining failures are another cause, below.

## The disk's stalls

Every failure in this snapshot is `wow.command.busy` (HTTP 429), and they come in bursts: PostgreSQL E1 at 25% answered every second at its offered rate except a few, each after a second whose p99 passed 1 s (3.6 s at second 33), while the longest WAL flush of the run was 2,183 ms. A flush that long holds every commit behind it; the node's 4,096 commands in flight fill, and the commands beyond are refused, as the first snapshot found with 1 and 2 GB of heap.

| Fixed run (PostgreSQL) | Offered | Failed | Seconds with a p99 over 1 s | Longest WAL flush |
| --- | --- | --- | --- | --- |
| E1 at 25%, after the sweeps | 5,251/s | 17,992 | 4 | 2,183 ms |
| E1, A/A session 2 | 10,503/s | 7,308 | 1 | 1,327 ms |
| E1, A/A session 3 | 10,503/s | 695 | 0 | 746 ms |
| E1, 1 GB heap | 8,949/s | 33,013 | 1 | 959 ms |
| E1, 2 GB heap | 8,949/s | 40,796 | 4 | 1,597 ms |

- The failures follow the flushes, not the load: the A/A sessions at twice the 25% rate failed 7,308 and 695 commands, their longest flushes 1,327 and 746 ms. The node's costs per command did not change with them (0.308 and 0.316 ms of CPU, 78.9 and 78.5 KB allocated, 3.6 rows read).
- MongoDB's fixed E1 runs (8,380/s at 1 and 2 GB) failed none; its 50% and 75% runs after the sweeps failed in seconds of checkpoints up to 1.5 s.
- The store's disk is Docker Desktop's disk image (`Docker.raw` over VirtioFS), not a server's. Refusing the commands beyond the in-flight bound while the store stalls is by design; what a deployment's disk does is outside this snapshot.

## Repeatability

The A/A sessions (PostgreSQL E1 at 10,503/s, fresh) agree within 1.1% on throughput and 2.5% on CPU per command (0.308 against 0.316 ms), as the first snapshot's did (2%). Their p50 is 4.6–5.1 ms against the first snapshot's 27.5–28.7 ms at 10,602/s, after D276–D280.

## 1 GB against 2 GB of heap

| | PostgreSQL 1 GB | PostgreSQL 2 GB | MongoDB 1 GB | MongoDB 2 GB |
| --- | --- | --- | --- | --- |
| E1 peak | ≥ 17,898/s | ≥ 18,708/s | ≥ 16,761/s | ≥ 16,466/s |
| Node CPU, allocated per op | 0.296 ms, 81.4 KB | 0.280 ms, 80.6 KB | 0.256 ms, 90.8 KB | 0.249 ms, 88.7 KB |

The heap still does not set the ceiling: the peaks are within 5% and the costs per command within 6%. The fixed runs' failures are the disk's, above.

## L1: the kernel and one store

| | Concurrency 16 | 64 | 256 | 256, first snapshot |
| --- | --- | --- | --- | --- |
| PostgreSQL E1 | 8,425/s, 1.18 round trips | 12,649/s, 0.51 | 16,113/s, 0.18 | 14,099/s, 0.42 |
| PostgreSQL E3 | 3,362/s, 2.80 | 4,384/s, 1.39 | 5,090/s, 0.44 | 4,906/s, 1.00 |
| MongoDB E1 | 5,202/s, 0.64 | 12,674/s, 0.21 | 20,769/s, 0.07 | 20,869/s, 0.07 |
| MongoDB E3 | 2,247/s, 1.48 | 4,361/s, 0.53 | 7,050/s, 0.20 | 6,746/s, 0.21 |

PostgreSQL's round trips per operation fell by more than half at every concurrency (E1 at 64: 1.05 to 0.51), the effect of D280; MongoDB's, D275's already, are unchanged.

## What follows

- No regression from D276–D280 on either backend; E3, the saga chain, gained the most.
- The fixed rates' failures are the environment's fsync stalls; they are not a reason to change the in-flight bound. The rc's evidence on a server disk is Actions' L3 and L4 (item 19).

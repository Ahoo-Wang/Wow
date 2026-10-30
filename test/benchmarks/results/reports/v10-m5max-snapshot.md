# V10 milestone snapshot on Apple M5 Max

The milestone saturation snapshot of Wow 10 on an Apple M5 Max: one node on disk (sweeps and fixed rates), the stores in memory (`--tmpfs`), three nodes on the same host, and the node on JDK 25. Run on 2026-10-30 at `cebdb88d0` (`main`), against the [second M4 Pro snapshot](v10-m4pro-snapshot-2.md) (`081c9f533`, 2026-10-25). Between the two, `main` moved by 326 commits and the machine changed, so a difference between them is not any one change's. The raw runs, scripts and tables are in [`v10-m5max-snapshot/`](v10-m5max-snapshot/) (`SUMMARY.md`, `COMPARE.md`). A snapshot records capacity; changes are decided by an ABAB against `main`.

## Setup

| | |
| --- | --- |
| Commit | `cebdb88d01e87a81fb23ff90fbd4d301ed1e7da8` (`cebdb88d0`, `main`) |
| Machine | Apple M5 Max, 18 cores, 64 GB, macOS 27.2, as the runs' `environment` records them (the CPU's brand string, its cores, `hw.memsize`); the runs do not record the model (`hw.model`) |
| Container engine | OrbStack 2.2.5, as the session log names it; the runs record Docker engine 29.8.2 with 16 CPUs and 32,110 MiB |
| Stores | PostgreSQL `max_wal_size=8GB`, `checkpoint_timeout=15min`, `wal_compression=lz4`, `shared_buffers=512MB`; MongoDB WiredTiger cache 2 GB |
| Node | One node (3 in stage 5), 1 GB heap (`-Xms1g -Xmx1g -XX:+UseG1GC`), Temurin 21.0.13+9 (25.0.5+9 in stage 6) |
| Data | 100,000 accounts (`--accounts=100000`), primed once per session (`--prime --seed-concurrency=128`), uniform keys unless noted |
| Sweep | Closed-loop concurrency 1, 4, 16, 64, 256, 1024, 4096 (`--growth=4 --max-concurrency=4096 --step=30 --warm-up=10`) |

## Ceilings

Closed-loop sweeps, 30 s a step, concurrency 1 to 4,096 (`--growth=4`), one session per backend on disk. The M4 Pro's sweeps stopped at 1,024:

| Peak, per second | PostgreSQL (M5 Max) | PostgreSQL (M4 Pro snap 2) | MongoDB (M5 Max) | MongoDB (M4 Pro snap 2) |
| --- | --- | --- | --- | --- |
| E1, uniform | ≥ 33,180 (top step) | ≥ 21,005 (top step) | ≥ 22,745 (top step) | ≥ 16,215 (top step) |
| E1, Zipf | ≥ 19,599 (top step) | 11,951 (256) | ≥ 17,871 (top step) | ≥ 13,345 (top step) |
| E1, one hot account | ≥ 21,191 (top step) | 9,054 (256) | ≥ 18,987 (top step) | 9,779 (256) |
| E3, uniform | 9,101 (1024) | ≥ 7,099 (top step) | 6,755 (1024) | ≥ 5,781 (top step) |

- E1 uniform peaked at the top step on both machines, so both peaks are lower bounds and their ratio is not a gain. At the M4 Pro's top step, 1,024, the M5 Max ran 25,688/s on PostgreSQL and 19,360/s on MongoDB, 22% and 19% more. Neither E1 uniform sweep failed a command.
- E3 peaked at 1,024 on both backends, 9,101/s on PostgreSQL and 6,755/s on MongoDB. At that step the M4 Pro ran 7,099/s and 5,781/s, so the M5 Max ran 28% and 17% more. At 4,096 its throughput fell to 5,818/s and 3,343/s ([below](#e3-past-its-knee)).
- E1 Zipf and the hot account (half the deposits on one account, `--hot-keys=1`) ran their most at 4,096, where the node refused commands as `wow.command.busy` (429): Zipf 82,807 on PostgreSQL and 81,031 on MongoDB, the hot account 590,131 and 558,260. Below 4,096 neither failed a command.

## E3 past its knee

Every single-node E3 sweep peaked at 1,024, on disk, in memory and on JDK 25, so the disk's fsyncs do not set the knee. Every E3 error of these sweeps is at 4,096:

| Session | E3 at 1,024 | E3 at 4,096 | `wow.command.busy` (429) | `wow.wait.timeout` (504) |
| --- | --- | --- | --- | --- |
| PostgreSQL, disk (1-S) | 9,101/s | 5,818/s | 301,588 | 791 |
| MongoDB, disk (1-S) | 6,755/s | 3,343/s | 202,247 | 2,535 |
| PostgreSQL, tmpfs (4-T) | 11,337/s | 5,397/s | 514,379 | 226 |
| MongoDB, tmpfs (4-T) | 8,725/s | 6,035/s | 334,822 | 2,042 |
| PostgreSQL, JDK 25 (6-J25) | 10,049/s | 7,133/s | 303,288 | 1,547 |

A busy command was not taken: its aggregate's lane was full or the node had its most commands in flight. These runs do not say which of the two refused them. A wait timeout is a transfer whose entry was not stored on the target within the wait. No step below 4,096 failed a transfer.

## Fixed-rate open-loop runs

After the four sweeps, each backend's first session ran E1 and E3 open loop at 25%, 50% and 75% of that scenario's uniform peak (30 s warm-up, 60 s measured):

| Phase | Offered | Throughput | Failed | p50 | p99 | Node CPU ms/op | Alloc KB/op | Reads / query per op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PG E1 25% | 8,295/s | 8,295.0/s | 0 | 4.6 ms | 139.3 ms | 0.358 ms | 109.6 KB | 7.59 tup, 3.26 blks |
| PG E1 50% | 16,590/s | 16,340.5/s | 14,972 (ovld) | 23.3 ms | 157.7 ms | 0.233 ms | 87.4 KB | 2.81 tup, 0.52 blks |
| PG E1 75% | 24,885/s | 24,235.2/s | 38,987 (ovld) | 52.5 ms | 126.0 ms | 0.200 ms | 87.1 KB | 2.30 tup, 0.63 blks |
| PG E3 25% | 2,275/s | 2,275.0/s | 0 | 8.5 ms | 581.6 ms | 0.865 ms | 234.2 KB | 11.79 tup, 5.10 blks |
| PG E3 50% | 4,550/s | 4,214.6/s | 20,127 (ovld) | 56.6 ms | 708.6 ms | 0.553 ms | 223.6 KB | 8.69 tup, 3.49 blks |
| PG E3 75% | 6,825/s | 5,372.4/s | 87,157 (ovld) | 99.3 ms | 798.7 ms | 0.517 ms | 228.0 KB | 8.15 tup, 3.83 blks |
| Mongo E1 25% | 5,686/s | 5,686.0/s | 0 | 5.8 ms | 127.5 ms | 0.345 ms | 106.6 KB | 0.003 queries |
| Mongo E1 50% | 11,373/s | 11,306.9/s | 3,967 (ovld) | 16.5 ms | 119.3 ms | 0.244 ms | 98.1 KB | 0.002 queries |
| Mongo E1 75% | 17,059/s | 16,840.0/s | 13,137 (ovld) | 49.2 ms | 158.7 ms | 0.203 ms | 94.9 KB | 0.001 queries |
| Mongo E3 25% | 1,689/s | 1,689.0/s | 0 | 9.9 ms | 59.7 ms | 0.963 ms | 290.0 KB | 0.012 queries |
| Mongo E3 50% | 3,378/s | 3,378.0/s | 0 | 20.7 ms | 256.0 ms | 0.645 ms | 259.6 KB | 0.005 queries |
| Mongo E3 75% | 5,066/s | 4,999.1/s | 4,013 (ovld) | 78.3 ms | 434.2 ms | 0.531 ms | 250.7 KB | 0.009 queries |

- Every failure in these runs is `generator-overload`. The generator keeps at most 2,000 operations in flight to a node (`load.inFlightCap`) and counts one scheduled past that without sending it. None is an answer from the node.
- The E1 runs that failed, and MongoDB's E3 at 75%, fell below 90% of the offered rate in two to four of their 60 seconds (PostgreSQL E1 at 50%: 8,492 answered in second 4, at a p99 of 647 ms). PostgreSQL's E3 at 50% and 75% fell below it in 20 and 37 seconds, with a p99 over 500 ms in 31 and 42: rates this session did not sustain open loop.
- The first fixed run after the sweeps, PostgreSQL E1 at 25%, read 7.59 rows and 3.26 disk blocks per command. The later E1 runs read 2.3–2.8 rows and 0.5–0.6 blocks, and the fresh A/A sessions at the 50% rate 2.8–2.9 rows and 0.002 blocks. MongoDB's fixed runs made 0.001–0.012 queries per command, against 0.343 at 25% in the first M4 Pro snapshot, before D278.

## The stores in memory (tmpfs)

One session per backend with the stores' data in memory (`--tmpfs`, 4-T-\*), E1 and E3 uniform sweeps, against the disk sessions (1-S-\*):

| Backend | Scenario | On-disk peak (1-S-\*) | Memory peak (4-T-\*, tmpfs) | Delta | Memory top step | Memory errors |
| --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | E1 uniform | ≥ 33,180/s | ≥ 36,020/s | +8.56% | Yes (≥, 4096) | 0 |
| PostgreSQL | E3 uniform | 9,101/s | 11,337/s | +24.58% | No (1024) | 514,605 (at 4096) |
| MongoDB | E1 uniform | ≥ 22,745/s | ≥ 25,618/s | +12.63% | Yes (≥, 4096) | 0 |
| MongoDB | E3 uniform | 6,755/s | 8,725/s | +29.15% | No (1024) | 336,864 (at 4096) |

- E3 peaked at 1,024 in memory as on disk, 24.6% higher on PostgreSQL and 29.2% higher on MongoDB, in one session each. Its errors are all at 4,096, past the knee: 514,379 busy and 226 wait timeouts on PostgreSQL, 334,822 and 2,042 on MongoDB ([above](#e3-past-its-knee)).
- E1 peaked at the top step in memory as on disk, with no error, so its +8.6% and +12.6% are between two lower bounds and are not gains.

## Repeatability

Two fresh sessions on PostgreSQL (2-S-postgres and 3-S-postgres) ran E1 open loop at 50% of the uniform peak (16,590/s):

| Session | Offered | Throughput | Failed | p50 | p99 | Node CPU ms/op | Alloc KB/op | WAL flush time, worst second |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Round 2 (2-S-postgres) | 16,590/s | 16,336.5/s | 15,208 (ovld) | 15.9 ms | 176.1 ms | 0.235 ms | 85.0 KB | 1,143 ms |
| Round 3 (3-S-postgres) | 16,590/s | 16,523.4/s | 3,995 (ovld) | 14.1 ms | 72.2 ms | 0.241 ms | 84.8 KB | 997 ms |
| Delta (R3 vs R2) | - | +1.14% | - | - | - | +2.95% | -0.16% | - |

- The two sessions agree within 1.1% on throughput, 3.0% on node CPU per command and 0.2% on allocation per command.
- Round 2 failed 15,208 operations as generator overload at a p99 of 176 ms, round 3 3,995 at 72 ms; their worst seconds of WAL flushing were 1,143 and 997 ms. Two sessions are too few to tie the overloads to the flushes.

## L1: the kernel and one store

L1 runs the kernel against one store in one JVM, without HTTP or the load generator (`bench.sh component`), 20,000 operations a run. Its counts are the result; its timings only indicate (a run at 1,024 lasts under 2 s):

| Backend | Scenario | Concurrency 16 | Concurrency 64 | Concurrency 256 | Concurrency 1024 |
| --- | --- | --- | --- | --- | --- |
| PostgreSQL | E1 | 6,845/s, 1.01 rt, 64.8 KB | 12,328/s, 0.39 rt, 59.2 KB | 18,306/s, 0.16 rt, 58.6 KB | 34,530/s, 0.05 rt, 59.1 KB |
| PostgreSQL | E3 | 2,743/s, 2.39 rt, 200.2 KB | 4,576/s, 1.06 rt, 191.6 KB | 6,202/s, 0.40 rt, 189.2 KB | 12,947/s, 0.11 rt, 192.6 KB |
| MongoDB | E1 | 4,528/s, 0.63 rt, 86.0 KB | 10,145/s, 0.21 rt, 72.2 KB | 19,203/s, 0.07 rt, 66.8 KB | 34,120/s, 0.02 rt, 63.8 KB |
| MongoDB | E3 | 1,805/s, 1.46 rt, 261.5 KB | 3,923/s, 0.49 rt, 228.3 KB | 6,566/s, 0.19 rt, 216.3 KB | 11,477/s, 0.06 rt, 208.4 KB |

- The store round trips per operation fall as concurrency rises, to 0.05 on PostgreSQL and 0.02 on MongoDB for E1 at 1,024 (0.11 and 0.06 for E3).
- Allocation per operation is 59–86 KB for E1 and 189–262 KB for E3, highest at 16 on MongoDB.

## Three nodes on one host (5-N3-\*)

Three nodes (`--nodes=3`) on both backends, the generator sending round robin over them:

| Backend | Scenario | 1-node peak (1-S-\*) | 3-node peak (5-N3-\*) | Ratio (3N / 1N) | 3-node peak concurrency | 3-node errors |
| --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | E1 uniform | ≥ 33,180/s | 15,039/s | 0.45x | 256 | 3,702 (at 1024) |
| PostgreSQL | E3 uniform | 9,101/s | 5,622/s | 0.62x | 256 | 4,331 (121 at 256) |
| MongoDB | E1 uniform | ≥ 22,745/s | 12,037/s | 0.53x | 256 | 1,917 (at 1024) |
| MongoDB | E3 uniform | 6,755/s | 1,565/s | 0.23x | 64 | 1,549 (177 at 256) |

- The three node JVMs, Kafka, the store's container and the load generator share the host's 18 cores. These sweeps measure that host, not three machines; the cluster on Actions is L4's subject.
- No three-node sweep reached 4,096. At 1,024 their throughput fell to 614–5,864/s, with `wow.command.unknown` (503) and `wow.wait.timeout` (504); E3 had both already at 256. A `wow.command.unknown` is an outcome-unknown forward: the node forwarded the command to its owner, which did not answer within the forward timeout on this starved single host, so whether it committed is unknown (a retry with the same request id is safe).
- PostgreSQL's sweeps at 1,024 also answered 7 (E1) and 9 (E3) commands with `wow.command.contended` (409: concurrent writers kept winning the version slot). These runs keep no node logs and do not explain them.
- MongoDB's E3 sweep answered 36 transfers in its first step (concurrency 1, p99 2.6 s), then 540/s at concurrency 4; these runs do not explain that step either.

## JDK 25 (6-J25-postgres)

A fresh PostgreSQL session with the node on Temurin 25.0.5+9 (its `serverJvm`), E1 and E3 uniform sweeps:

| Scenario | JDK 21 peak (1-S-postgres) | JDK 25 peak (6-J25-postgres) | Delta | JDK 25 top step | Server JVM |
| --- | --- | --- | --- | --- | --- |
| E1 uniform | ≥ 33,180/s | ≥ 34,213/s | +3.11% | Yes (≥, 4096) | `openjdk version "25.0.5" 2026-10-20` |
| E3 uniform | 9,101/s | 10,049/s | +10.42% | No (1024) | `openjdk version "25.0.5" 2026-10-20` |

- There is one session per JDK and no A/A of the sweeps, so these differences are directional. E3 peaked 10.4% higher at 1,024. E1 was 3.1% higher at 4,096, between two lower bounds, and 3.2% lower at 1,024 (24,874/s against 25,688/s). The JDK 21 session's E3 sweep ran after three other sweeps, the JDK 25 session's after one.
- Its E3 errors (304,835) are all at 4,096, as on JDK 21 ([above](#e3-past-its-knee)); its E1 sweep failed none.

## The failures

Every code the runs recorded, and where:

1. `wow.command.busy` (429) on one node, only at 4,096: E1 Zipf and the hot account, and E3 in every session. `wow.wait.timeout` (504) with it on E3. E1 uniform failed none.
2. `generator-overload` in the fixed-rate runs and the A/A sessions, and nothing else: operations the generator did not send past its 2,000 in flight.
3. On three nodes, `wow.command.unknown` (503) and `wow.wait.timeout` (504) at 1,024, and on E3 at 256, from forwards on one starved host. PostgreSQL's 7 and 9 `wow.command.contended` (409) at 1,024 are not explained.

No run answered a 500. Every session ran its runs to the end, its nodes quiet after each (`settle.json`).

## What follows

- One node on disk: E1 ≥ 33,180/s on PostgreSQL and ≥ 22,745/s on MongoDB at the top step, E3 9,101/s and 6,755/s at 1,024. In memory: E1 ≥ 36,020/s and ≥ 25,618/s, E3 11,337/s and 8,725/s.
- JDK 25 ran E3 10.4% faster in one session. An A/A of the sweeps on each JDK would tell whether that holds; D368's recommendation of JDK 25 rests on the pinning it names, which this snapshot did not measure.
- MongoDB's state cache held the accounts across the sweeps and the fixed runs. PostgreSQL's first run after the sweeps read 7.59 rows and 3.26 disk blocks per command, the later ones 2.3–2.8 rows and 0.5–0.6 blocks.

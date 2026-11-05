## m5max-axon-pool-32@100k

Both pools at 32 connections instead of Spring Boot’s 10, on the M5 Max: E1 and E3 to their peaks, W A A W (Axon’s 16 processor threads share 10 connections by default).

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×2 a step to 4096 and on while it rises, to 12288 at most, located first with 8 s steps after 3 s and measured at full length around the peak and the sustainable rate, a failing step ending after 10 s; 2 round(s) of one session per arm, ABBA. Runner: unknown, ? cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed; every run on it first waited until the nodes were quiet.

- `wow-pool-32`: spring.datasource.hikari.maximum-pool-size=32
- `axon-pool-32`: spring.datasource.hikari.maximum-pool-size=32

Sessions, in order: 1-wow-pool-32, 1-axon-pool-32, 2-axon-pool-32, 2-wow-pool-32.

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow-pool-32/axon-pool-32 from the lowest wow-pool-32 peak over the highest axon-pool-32 peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow-pool-32 | axon-pool-32 | wow-pool-32/axon-pool-32 | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E3 | 12386/s at 2048; 12510/s at 2048 | 3742/s at 128; 3718/s at 256 | 3.31–3.36 | 1.0% | wow-pool-32 higher |

The load generator used at most 0.97 of its 18 CPUs at a peak; no Kafka ran, as neither server uses one.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow-pool-32 | axon-pool-32 | wow-pool-32/axon-pool-32 | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E3 | 11572/s at 1024 (p99 143.4 ms); 11713/s at 1024 (p99 136.2 ms) | 3684/s at 64 (p99 68.1 ms); 3698/s at 64 (p99 65.3 ms) | 3.13–3.18 | 1.2% | wow-pool-32 higher |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32 | 100000 (100000) | 100001 (100001–100001) | 1729798 (1725281–1893924) | 1729798 (1729798) | 1729798 (1729798) | 1729798 (1729798) | 0 (0) |
| 1-axon-pool-32 | 100000 (100000) | 100001 (100001–100001) | 812455 (812455–812491) | 812455 (812455) | 812455 (812455) | 812455 (812455) | 0 (0) |
| 2-axon-pool-32 | 100000 (100000) | 100001 (100001–100001) | 994285 (994272–994312) | 994285 (994285) | 994285 (994285) | 994285 (994285) | 0 (0) |
| 2-wow-pool-32 | 100000 (100000) | 100001 (100001–100001) | 1739683 (1735536–1927894) | 1739683 (1739683) | 1739683 (1739683) | 1739683 (1739683) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32 | 5389395 | 7119193 | 4065.1 MB | 599 | 100000 | 49.5 MB | 519 | 1.1 MB |
| 1-axon-pool-32 | 3449821 | 3449821 | 1655.9 MB | 503 | 258 | 0.2 MB | 889 | 1.6 MB |
| 2-axon-pool-32 | 4177141 | 4177141 | 2009.8 MB | 505 | 1001 | 0.5 MB | 540 | 1.6 MB |
| 2-wow-pool-32 | 5419050 | 7158733 | 4088.3 MB | 599 | 100000 | 43.9 MB | 460 | 1.4 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow-pool-32 | 1.8 s | 513.4 MB | 1491.6 MB |
| 1-axon-pool-32 | 1.2 s | 366.6 MB | 1590.8 MB |
| 2-axon-pool-32 | 1.1 s | 361.2 MB | 1597.4 MB |
| 2-wow-pool-32 | 1.8 s | 500.4 MB | 1494.6 MB |

The sweeps' steps (throughput, and p99 or the errors):

| Sweep | Arm | Peak | Stopped | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32-e3-sweep | wow-pool-32 | 12386/s at 2048 | more than 1% failed | - | - | - | - | 11572/s, p99 143.4 ms | 12386/s, p99 288.8 ms |
| 1-axon-pool-32-e3-sweep | axon-pool-32 | 3742/s at 128 | peaked below its top step | 3684/s, p99 68.1 ms | 3742/s, p99 215.0 ms | 2636/s, p99 708.6 ms | - | - | - |
| 2-axon-pool-32-e3-sweep | axon-pool-32 | 3718/s at 256 | peaked below its top step | 3698/s, p99 65.3 ms | 3629/s, p99 212.0 ms | 3718/s, p99 495.6 ms | 3612/s, p99 1556.5 ms | - | - |
| 2-wow-pool-32-e3-sweep | wow-pool-32 | 12510/s at 2048 | more than 1% failed | - | - | - | - | 11713/s, p99 136.2 ms | 12510/s, p99 274.4 ms |

The short steps that located them (3 s warm-up, 8 s measured), not part of the results:

| Sweep | Arm | 1 clients | 2 clients | 4 clients | 8 clients | 16 clients | 32 clients | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-pool-32-e3-sweep | wow-pool-32 | 179/s, p99 13.3 ms | 630/s, p99 9.8 ms | 1107/s, p99 6.3 ms | 1804/s, p99 7.2 ms | 2899/s, p99 9.6 ms | 3202/s, p99 23.4 ms | 5511/s, p99 19.5 ms | 6737/s, p99 33.5 ms | 5648/s, p99 64.0 ms | 8015/s, p99 90.6 ms | 12525/s, p99 129.0 ms | 14117/s, p99 228.4 ms | 6797/s, 37988 errors |
| 1-axon-pool-32-e3-sweep | axon-pool-32 | 192/s, p99 13.4 ms | 806/s, p99 3.9 ms | 1317/s, p99 8.1 ms | 2161/s, p99 7.4 ms | 2995/s, p99 13.4 ms | 3484/s, p99 29.7 ms | 3745/s, p99 62.7 ms | 3881/s, p99 145.4 ms | 3890/s, p99 294.9 ms | 3708/s, p99 892.9 ms | 3787/s, p99 1589.2 ms | 3674/s, p99 1654.8 ms | 3249/s, p99 1900.5 ms |
| 2-axon-pool-32-e3-sweep | axon-pool-32 | 518/s, p99 2.7 ms | 816/s, p99 3.7 ms | 1301/s, p99 9.1 ms | 2166/s, p99 7.1 ms | 2952/s, p99 13.8 ms | 3440/s, p99 32.8 ms | 3750/s, p99 61.7 ms | 3306/s, p99 189.4 ms | 3851/s, p99 350.2 ms | 3780/s, p99 593.9 ms | 3817/s, p99 819.2 ms | 3689/s, p99 1826.8 ms | 3262/s, p99 2097.2 ms |
| 2-wow-pool-32-e3-sweep | wow-pool-32 | 455/s, p99 7.2 ms | 821/s, p99 3.7 ms | 1116/s, p99 6.7 ms | 1839/s, p99 7.0 ms | 2843/s, p99 9.9 ms | 3994/s, p99 14.0 ms | 5279/s, p99 20.2 ms | 6135/s, p99 61.4 ms | 5593/s, p99 69.1 ms | 7773/s, p99 101.9 ms | 12481/s, p99 137.2 ms | 14025/s, p99 232.4 ms | 7480/s, 71171 errors |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |


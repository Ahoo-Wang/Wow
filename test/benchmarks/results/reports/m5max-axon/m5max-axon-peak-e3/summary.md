## m5max-axon-peak@100k

The industry comparison on the M5 Max: Wow with no bus (wow.transport=none) against Axon Framework 4 on one PostgreSQL, one node each, W A A W: closed-loop sweeps of E1 and E3 to their peaks, then each at half of its own sustainable rate.

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×2 a step to 4096 and on while it rises, to 12288 at most, located first with 8 s steps after 3 s and measured at full length around the peak and the sustainable rate, a failing step ending after 10 s; 2 round(s) of one session per arm, ABBA. Runner: unknown, ? cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed (7–14 s); every run on it first waited until the nodes were quiet (3–4 s).

Sessions, in order: 1-wow, 1-axon, 2-axon, 2-wow.

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow/axon from the lowest wow peak over the highest axon peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E3 | 12404/s at 2048; 12042/s at 2048 | 2314/s at 64; 2326/s at 128 | 5.18–5.36 | 3.0% | wow higher |

The load generator used at most 0.95 of its 18 CPUs at a peak; no Kafka ran, as neither server uses one.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E3 | 11655/s at 1024 (p99 149.5 ms); 11668/s at 1024 (p99 140.3 ms) | 2314/s at 64 (p99 100.4 ms); 2320/s at 32 (p99 38.9 ms) | 5.02–5.04 | 0.2% | wow higher |

**Own share**: with the same headroom, each one's latency and cost per command. Each arm at 50% of the sustainable rate its own sweep found in the session, after the sweeps, in session order.

| Scenario | Metric | wow | axon |
| --- | --- | --- | --- |
| E3 | Rate | 5827/s, 5834/s | 1157/s, 1160/s |
| E3 | Throughput | 5752.7/s, 5743.3/s | 1083.7/s, 1159.8/s |
| E3 | Kept up | **no**, **no** | **no**, yes |
| E3 | Failed | 2985, 3631 | 2942, 0 |
| E3 | p50 | 69.12 ms, 68.61 ms | 3.95 ms, 2.99 ms |
| E3 | p99 | 454.7 ms, 434.2 ms | 7340.0 ms, 38.7 ms |
| E3 | Node CPU ms/op | 0.392, 0.386 | 0.861, 0.927 |
| E3 | PostgreSQL CPU ms/op | 0.254, 0.251 | 0.623, 0.681 |
| E3 | PostgreSQL transactions/op | 0.28, 0.27 | 4.48, 5.44 |
| E3 | PostgreSQL WAL bytes/op | 3.8 KB, 3.8 KB | 3.0 KB, 3.1 KB |
| E3 | Node alloc/op | 182.8 KB, 183.7 KB | 279.5 KB, 257.6 KB |
| E3 | Node GC time | 0.7%, 0.7% | 0.7%, 1.4% |
| E3 | Node RSS max | 1498 MB, 1495 MB | 1605 MB, 1610 MB |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 100000 (100000) | 100001 (100001–100001) | 2082981 (2080150–2337854) | 2082981 (2082981) | 2082981 (2082981) | 2082981 (2082981) | 0 (0) |
| 1-axon | 100000 (100000) | 100001 (100001–100001) | 623940 (623813–623940) | 623940 (623940) | 623940 (623940) | 623940 (623940) | 0 (0) |
| 2-axon | 100000 (100000) | 100001 (100001–100001) | 633495 (633495–634175) | 633495 (633495) | 633495 (633495) | 633495 (633495) | 0 (0) |
| 2-wow | 100000 (100000) | 100001 (100001–100001) | 2072084 (2068207–2385419) | 2072084 (2072084) | 2072084 (2072084) | 2072084 (2072084) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 6448944 | 8531925 | 4879.4 MB | 600 | 100000 | 52.8 MB | 553 | 1.0 MB |
| 1-axon | 2695761 | 2695761 | 1285.0 MB | 500 | 46 | 0.1 MB | 2671 | 0.4 MB |
| 2-axon | 2733981 | 2733981 | 1304.4 MB | 500 | 51 | 0.1 MB | 2249 | 0.5 MB |
| 2-wow | 6416253 | 8488337 | 4855.1 MB | 600 | 100000 | 51.5 MB | 540 | 1.0 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow | 1.8 s | 486.4 MB | 1497.8 MB |
| 1-axon | 1.1 s | 366.9 MB | 1605.0 MB |
| 2-axon | 1.1 s | 396.9 MB | 1610.0 MB |
| 2-wow | 1.8 s | 484.6 MB | 1495.2 MB |

The sweeps' steps (throughput, and p99 or the errors):

| Sweep | Arm | Peak | Stopped | 32 clients | 64 clients | 128 clients | 1024 clients | 2048 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e3-sweep | wow | 12404/s at 2048 | more than 1% failed | - | - | - | 11655/s, p99 149.5 ms | 12404/s, p99 276.5 ms |
| 1-axon-e3-sweep | axon | 2314/s at 64 | peaked below its top step | 2311/s, p99 42.0 ms | 2314/s, p99 100.4 ms | 2195/s, p99 356.4 ms | - | - |
| 2-axon-e3-sweep | axon | 2326/s at 128 | peaked below its top step | 2320/s, p99 38.9 ms | 2311/s, p99 95.2 ms | 2326/s, p99 389.1 ms | - | - |
| 2-wow-e3-sweep | wow | 12042/s at 2048 | more than 1% failed | - | - | - | 11668/s, p99 140.3 ms | 12042/s, p99 667.6 ms |

The short steps that located them (3 s warm-up, 8 s measured), not part of the results:

| Sweep | Arm | 1 clients | 2 clients | 4 clients | 8 clients | 16 clients | 32 clients | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e3-sweep | wow | 348/s, p99 7.7 ms | 214/s, p99 21.8 ms | 1053/s, p99 11.0 ms | 1816/s, p99 7.6 ms | 2909/s, p99 9.7 ms | 4166/s, p99 13.6 ms | 5486/s, p99 19.6 ms | 6848/s, p99 32.8 ms | 5605/s, p99 66.6 ms | 7851/s, p99 98.3 ms | 12632/s, p99 153.6 ms | 14031/s, p99 272.4 ms | 6766/s, 108248 errors |
| 1-axon-e3-sweep | axon | 520/s, p99 2.7 ms | 801/s, p99 5.9 ms | 1339/s, p99 4.8 ms | 2043/s, p99 7.5 ms | 2236/s, p99 27.5 ms | 2362/s, p99 36.9 ms | 2395/s, p99 104.4 ms | 2037/s, p99 342.0 ms | 2369/s, p99 536.6 ms | 2371/s, p99 725.0 ms | 2309/s, p99 1073.2 ms | 2299/s, p99 3506.2 ms | 1971/s, p99 2867.2 ms |
| 2-axon-e3-sweep | axon | 514/s, p99 2.7 ms | 785/s, p99 5.2 ms | 1347/s, p99 4.8 ms | 2038/s, p99 7.4 ms | 2284/s, p99 17.2 ms | 2353/s, p99 35.3 ms | 2356/s, p99 109.1 ms | 2321/s, p99 208.9 ms | 2353/s, p99 569.3 ms | 2349/s, p99 761.9 ms | 2334/s, p99 1548.3 ms | 2307/s, p99 3506.2 ms | 1896/s, p99 3194.9 ms |
| 2-wow-e3-sweep | wow | 604/s, p99 2.6 ms | 842/s, p99 3.5 ms | 1184/s, p99 5.1 ms | 1778/s, p99 9.3 ms | 2882/s, p99 9.6 ms | 4171/s, p99 13.2 ms | 5524/s, p99 20.0 ms | 6258/s, p99 35.8 ms | 5736/s, p99 59.4 ms | 7832/s, p99 114.7 ms | 12481/s, p99 161.8 ms | 14204/s, p99 217.1 ms | 4726/s, 163459 errors |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e3-own | wow | 5827.3/s | 5752.7/s | 230107 | 2985 | 69.1 ms | 155.6 ms | 454.7 ms | n/a | 569.3 ms | 2.26 cores | postgres 146% | 1.03 of 18 | server 2.26 | 0.65 | 182.8 KB | - | - | generator-overload 2985 |
| 1-axon-e3-own | axon | 1157.2/s | 1083.7/s | 43346 | 2942 | 4.0 ms | 5341.2 ms | 7340.0 ms | n/a | 8224.8 ms | 0.93 cores | postgres 67% | 0.21 of 18 | server 0.93 | 1.48 | 279.5 KB | - | - | generator-overload 2942 |
| 2-axon-e3-own | axon | 1159.8/s | 1159.8/s | 46392 | 0 | 3.0 ms | 5.3 ms | 38.7 ms | n/a | 233.5 ms | 1.08 cores | postgres 79% | 0.18 of 18 | server 1.08 | 1.61 | 257.6 KB | - | - | - |
| 2-wow-e3-own | wow | 5834.1/s | 5743.3/s | 229733 | 3631 | 68.6 ms | 252.9 ms | 434.2 ms | n/a | 522.2 ms | 2.22 cores | postgres 144% | 1.02 of 18 | server 2.22 | 0.64 | 183.7 KB | - | - | generator-overload 3631 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| m5max-axon-peak@100k | 1-wow-e3-own | steady | **error spike** | 2985 of 233092 failed: generator-overload 2985; generator-overload: 2000 in flight at most (per-target ceiling) |
| m5max-axon-peak@100k | 1-axon-e3-own | steady | **unstable** | second half against first, beyond the default limits: throughput +14.6% (±5.0%), p50 -99.6% (±25.0%) |
| m5max-axon-peak@100k | 1-axon-e3-own | steady | **error spike** | 2942 of 46288 failed: generator-overload 2942; generator-overload: 2000 in flight at most (per-target ceiling) |
| m5max-axon-peak@100k | 1-axon-e3-own | steady | **p99 > 10× p50** | p50 4.0 ms, p99 7340.0 ms |
| m5max-axon-peak@100k | 2-wow-e3-own | steady | **error spike** | 3631 of 233364 failed: generator-overload 3631; generator-overload: 2000 in flight at most (per-target ceiling) |


## m5max-axon-contention@100k

Contention on the M5 Max: E1 and E3 with Zipf keys and E1 on hot keys to their peaks, Wow with no bus against Axon Framework 4, W A A W.

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×2 a step to 4096 and on while it rises, to 12288 at most, located first with 8 s steps after 3 s and measured at full length around the peak and the sustainable rate, a failing step ending after 10 s; 2 round(s) of one session per arm, ABBA. Runner: unknown, ? cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed; every run on it first waited until the nodes were quiet.

Sessions, in order: 1-wow, 1-axon, 2-axon, 2-wow.

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow/axon from the lowest wow peak over the highest axon peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 zipf | 23812/s at 2048; ≥ 23352/s at 2048 (top step) | 6142/s at 512; 6220/s at 256 | ≥ 3.75–3.88 | 2.0% | wow higher |

The load generator used at most 2.16 of its 18 CPUs at a peak, **more than 1.6**; no Kafka ran, as neither server uses one.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 zipf | 19837/s at 512 (p99 135.2 ms); 18781/s at 512 (p99 144.4 ms) | 6142/s at 512 (p99 182.3 ms); 6220/s at 256 (p99 132.1 ms) | 3.02–3.23 | 5.6% | wow higher |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 100000 (100000) | 4398198 (4398017–4466207) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-axon | 100000 (100000) | 1275537 (1275005–1275595) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-axon | 100000 (100000) | 1579035 (1578758–1579279) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-wow | 100000 (100000) | 4095741 (4095741–4122972) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 4498201 | 4498202 | 2692.4 MB | 628 | 100000 | 41.8 MB | 438 | 1.4 MB |
| 1-axon | 1375541 | 1375541 | 617.5 MB | 471 | 820 | 0.9 MB | 1129 | 0.1 MB |
| 2-axon | 1679039 | 1679039 | 753.2 MB | 470 | 1018 | 0.9 MB | 958 | 0.1 MB |
| 2-wow | 4195744 | 4195745 | 2513.4 MB | 628 | 100000 | 41.9 MB | 439 | 1.4 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow | 1.8 s | 464.7 MB | 1494.6 MB |
| 1-axon | 1.1 s | 378.9 MB | 1616.2 MB |
| 2-axon | 1.1 s | 378.9 MB | 1610.2 MB |
| 2-wow | 1.8 s | 471.1 MB | 1475.4 MB |

The sweeps' steps (throughput, and p99 or the errors):

| Sweep | Arm | Peak | Stopped | 256 clients | 512 clients | 1024 clients | 2048 clients |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-zipf-sweep | wow | 23812/s at 2048 | more than 1% failed | - | 19837/s, p99 135.2 ms | 19266/s, p99 346.1 ms | 23812/s, 12528 errors |
| 1-axon-e1-zipf-sweep | axon | 6142/s at 512 | peaked below its top step | 6133/s, p99 135.2 ms | 6142/s, p99 182.3 ms | - | - |
| 2-axon-e1-zipf-sweep | axon | 6220/s at 256 | peaked below its top step | 6220/s, p99 132.1 ms | 6143/s, p99 181.2 ms | 6049/s, p99 307.2 ms | - |
| 2-wow-e1-zipf-sweep | wow | ≥ 23352/s at 2048 (top step) | more than 1% failed | - | 18781/s, p99 144.4 ms | 19236/s, p99 362.5 ms | 23352/s, 18769 errors |

The short steps that located them (3 s warm-up, 8 s measured), not part of the results:

| Sweep | Arm | 1 clients | 2 clients | 4 clients | 8 clients | 16 clients | 32 clients | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-zipf-sweep | wow | 1447/s, p99 1.1 ms | 2138/s, p99 1.3 ms | 2640/s, p99 2.7 ms | 2953/s, p99 9.3 ms | 5540/s, p99 8.2 ms | 9221/s, p99 8.0 ms | 13348/s, p99 10.9 ms | 17426/s, p99 16.9 ms | 19098/s, p99 36.9 ms | 20095/s, p99 136.2 ms | 19983/s, p99 335.9 ms | 24051/s, 1078 errors | 26385/s, 28564 errors |
| 1-axon-e1-zipf-sweep | axon | 1351/s, p99 1.3 ms | 2082/s, p99 1.5 ms | 2088/s, p99 6.0 ms | 5123/s, p99 4.7 ms | 5772/s, p99 9.7 ms | 6125/s, p99 12.5 ms | 6094/s, p99 26.9 ms | 5950/s, p99 53.0 ms | 6246/s, p99 129.5 ms | 6238/s, p99 171.0 ms | 4016/s, p99 745.5 ms | 6240/s, p99 419.8 ms | 3891/s, p99 2277.4 ms |
| 2-axon-e1-zipf-sweep | axon | 1367/s, p99 1.3 ms | 2093/s, p99 1.8 ms | 3576/s, p99 2.2 ms | 5203/s, p99 4.6 ms | 5816/s, p99 9.3 ms | 6071/s, p99 13.0 ms | 6069/s, p99 28.0 ms | 6022/s, p99 52.5 ms | 6256/s, p99 130.0 ms | 6250/s, p99 170.0 ms | 6302/s, p99 252.9 ms | 6191/s, p99 430.1 ms | 6077/s, p99 1269.8 ms |
| 2-wow-e1-zipf-sweep | wow | 1444/s, p99 1.1 ms | 1164/s, p99 5.1 ms | 2618/s, p99 2.8 ms | 3803/s, p99 4.9 ms | 6064/s, p99 6.0 ms | 9208/s, p99 8.0 ms | 13441/s, p99 10.8 ms | 17311/s, p99 16.9 ms | 18769/s, p99 37.9 ms | 19998/s, p99 139.3 ms | 20909/s, p99 315.4 ms | 24760/s, 3144 errors | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |


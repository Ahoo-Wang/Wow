## m5max-axon-contention@100k

Contention on the M5 Max: E1 and E3 with Zipf keys and E1 on hot keys to their peaks, Wow with no bus against Axon Framework 4, W A A W.

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×2 a step to 4096 and on while it rises, to 12288 at most, located first with 8 s steps after 3 s and measured at full length around the peak and the sustainable rate, a failing step ending after 10 s; 2 round(s) of one session per arm, ABBA. Runner: unknown, ? cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed; every run on it first waited until the nodes were quiet.

Sessions, in order: 1-wow, 1-axon, 2-axon, 2-wow.

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow/axon from the lowest wow peak over the highest axon peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 hot | ≥ 23943/s at 2048 (top step); ≥ 23634/s at 2048 (top step) | 2654/s at 256; 2633/s at 256 | ≥ 8.90–9.09 | 1.3% | wow higher |

The load generator used at most 3.77 of its 18 CPUs at a peak, **more than 1.6**; no Kafka ran, as neither server uses one.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 hot | 14274/s at 128 (p99 19.5 ms); 13618/s at 256 (p99 49.7 ms) | 2654/s at 256 (p99 177.2 ms); 2633/s at 256 (p99 178.2 ms) | 5.13–5.42 | 4.8% | wow higher |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 100000 (100000) | 4120612 (4120548–5134824) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 1-axon | 100000 (100000) | 649967 (649787–650019) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-axon | 100000 (100000) | 661493 (661320–661538) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |
| 2-wow | 100000 (100000) | 3863025 (3862965–4911069) | 1 (1–1) | 1 (1) | 1 (1) | 1 (1) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 4220615 | 4220616 | 2495.6 MB | 620 | 100000 | 41.9 MB | 440 | 1.2 MB |
| 1-axon | 749971 | 749971 | 338.5 MB | 473 | 1 | 0.3 MB | 278528 | 0.0 MB |
| 2-axon | 761497 | 761497 | 343.8 MB | 473 | 1 | 0.3 MB | 278528 | 0.1 MB |
| 2-wow | 3963028 | 3963029 | 2342.8 MB | 620 | 100000 | 42.0 MB | 440 | 1.3 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow | 1.8 s | 465.4 MB | 1462.4 MB |
| 1-axon | 1.2 s | 371.2 MB | 1585.0 MB |
| 2-axon | 1.1 s | 397.6 MB | 1610.1 MB |
| 2-wow | 1.8 s | 470.4 MB | 1483.6 MB |

The sweeps' steps (throughput, and p99 or the errors):

| Sweep | Arm | Peak | Stopped | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-hot-sweep | wow | ≥ 23943/s at 2048 (top step) | more than 1% failed | 14274/s, p99 19.5 ms | 10978/s, p99 209.9 ms | 9434/s, p99 274.4 ms | 11510/s, p99 374.8 ms | 23943/s, 619015 errors |
| 1-axon-e1-hot-sweep | axon | 2654/s at 256 | peaked below its top step | - | 2654/s, p99 177.2 ms | 2638/s, p99 288.8 ms | - | - |
| 2-axon-e1-hot-sweep | axon | 2633/s at 256 | peaked below its top step | - | 2633/s, p99 178.2 ms | 2598/s, p99 297.0 ms | - | - |
| 2-wow-e1-hot-sweep | wow | ≥ 23634/s at 2048 (top step) | more than 1% failed | - | 13618/s, p99 49.7 ms | 12997/s, p99 119.8 ms | 12539/s, p99 364.5 ms | 23634/s, 622881 errors |

The short steps that located them (3 s warm-up, 8 s measured), not part of the results:

| Sweep | Arm | 1 clients | 2 clients | 4 clients | 8 clients | 16 clients | 32 clients | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-hot-sweep | wow | 711/s, p99 3.6 ms | 2015/s, p99 1.6 ms | 2625/s, p99 2.7 ms | 3787/s, p99 4.5 ms | 5915/s, p99 5.4 ms | 8958/s, p99 7.4 ms | 7720/s, p99 20.9 ms | 14500/s, p99 19.1 ms | 10887/s, p99 205.8 ms | 14252/s, p99 105.5 ms | 14844/s, p99 151.6 ms | 24155/s, 165974 errors | - |
| 1-axon-e1-hot-sweep | axon | 1370/s, p99 1.2 ms | 1934/s, p99 1.9 ms | 2459/s, p99 3.2 ms | 2413/s, p99 7.9 ms | 2538/s, p99 20.6 ms | 2622/s, p99 28.9 ms | 939/s, p99 260.1 ms | 2558/s, p99 139.3 ms | 2697/s, p99 173.1 ms | 2634/s, p99 274.4 ms | 2695/s, p99 460.8 ms | 2657/s, p99 860.2 ms | 2570/s, p99 1794.0 ms |
| 2-axon-e1-hot-sweep | axon | 1346/s, p99 1.3 ms | 1942/s, p99 2.0 ms | 2428/s, p99 3.3 ms | 2385/s, p99 8.1 ms | 2255/s, p99 26.4 ms | 2613/s, p99 28.0 ms | 2620/s, p99 42.5 ms | 2597/s, p99 101.9 ms | 2670/s, p99 173.1 ms | 2642/s, p99 274.4 ms | 2655/s, p99 469.0 ms | 2593/s, p99 876.5 ms | 2552/s, p99 1851.4 ms |
| 2-wow-e1-hot-sweep | wow | 1259/s, p99 2.8 ms | 1985/s, p99 1.6 ms | 2612/s, p99 2.7 ms | 3782/s, p99 4.7 ms | 5917/s, p99 5.4 ms | 8954/s, p99 7.3 ms | 12680/s, p99 10.6 ms | 14700/s, p99 18.8 ms | 14722/s, p99 41.5 ms | 12902/s, p99 278.5 ms | 11097/s, p99 380.9 ms | 23773/s, 175181 errors | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |


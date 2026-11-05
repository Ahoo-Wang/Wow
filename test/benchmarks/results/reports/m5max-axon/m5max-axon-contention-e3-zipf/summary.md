## m5max-axon-contention@100k

Contention on the M5 Max: E1 and E3 with Zipf keys and E1 on hot keys to their peaks, Wow with no bus against Axon Framework 4, W A A W.

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×2 a step to 4096 and on while it rises, to 12288 at most, located first with 8 s steps after 3 s and measured at full length around the peak and the sustainable rate, a failing step ending after 10 s; 2 round(s) of one session per arm, ABBA. Runner: unknown, ? cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed; every run on it first waited until the nodes were quiet.

Sessions, in order: 1-wow, 1-axon, 2-axon, 2-wow.

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow/axon from the lowest wow peak over the highest axon peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E3 zipf | 2447/s at 64; 2562/s at 32 | 1785/s at 32; 1769/s at 64 | 1.37–1.45 | 4.7% | not comparable (wow: the store check) |

The load generator used at most 0.37 of its 18 CPUs at a peak; no Kafka ran, as neither server uses one.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E3 zipf | 2447/s at 64 (p99 169.0 ms); 2562/s at 32 (p99 65.0 ms) | 1785/s at 32 (p99 90.1 ms); 1769/s at 64 (p99 189.4 ms) | 1.37–1.45 | 4.7% | not comparable (wow: the store check) |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit). **Not comparable: wow.**

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 100000 (100000) | 100001 (100001–100001) | 555013 (547513–616749) | 555013 (555013) | **552570 (555013)** | **548931 (552570)** | 0 (0) |
| 1-axon | 100000 (100000) | 100001 (100001–100001) | 568898 (567186–569365) | 568898 (568898) | 568898 (568898) | 568898 (568898) | 0 (0) |
| 2-axon | 100000 (100000) | 100001 (100001–100001) | 492397 (491299–494074) | 492397 (492397) | 492397 (492397) | 492397 (492397) | 0 (0) |
| 2-wow | 100000 (100000) | 100001 (100001–100001) | 740979 (728367–796440) | 740979 (740979) | 740979 (740979) | 740979 (740979) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 1856515 | 2411528 | 1415.7 MB | 616 | 100000 | 41.7 MB | 438 | 1.6 MB |
| 1-axon | 2475593 | 2475593 | 1212.3 MB | 513 | 1607 | 1.2 MB | 785 | 0.6 MB |
| 2-axon | 2169589 | 2169589 | 1061.9 MB | 513 | 1404 | 1.2 MB | 869 | 0.6 MB |
| 2-wow | 2422938 | 3163917 | 1853.0 MB | 614 | 100000 | 41.9 MB | 439 | 1.6 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow | 1.8 s | 462.5 MB | 1527.1 MB |
| 1-axon | 1.1 s | 403.2 MB | 1593.2 MB |
| 2-axon | 1.1 s | 382.0 MB | 1605.4 MB |
| 2-wow | 1.8 s | 475.7 MB | 1509.8 MB |

The sweeps' steps (throughput, and p99 or the errors):

| Sweep | Arm | Peak | Stopped | 32 clients | 64 clients | 128 clients | 2048 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e3-zipf-sweep | wow | 2447/s at 64 | more than 1% failed | 1984/s, p99 129.5 ms | 2447/s, p99 169.0 ms | 102/s, 384 errors | - | 871/s, 3822 errors in 10 s |
| 1-axon-e3-zipf-sweep | axon | 1785/s at 32 | levelled off | 1785/s, p99 90.1 ms | 1759/s, p99 202.8 ms | 1750/s, p99 405.5 ms | 1713/s, p99 6553.6 ms | 1748/s, 1083 errors in 15 s |
| 2-axon-e3-zipf-sweep | axon | 1769/s at 64 | levelled off | - | 1769/s, p99 189.4 ms | 1623/s, p99 643.1 ms | 1719/s, p99 6717.4 ms | 1765/s, 652 errors in 13 s |
| 2-wow-e3-zipf-sweep | wow | 2562/s at 32 | more than 1% failed | 2562/s, p99 65.0 ms | 1995/s, p99 335.9 ms | 2302/s, p99 630.8 ms | - | 2543/s, 8313 errors |

The short steps that located them (3 s warm-up, 8 s measured), not part of the results:

| Sweep | Arm | 1 clients | 2 clients | 4 clients | 8 clients | 16 clients | 32 clients | 64 clients | 128 clients | 256 clients | 512 clients | 1024 clients | 2048 clients | 4096 clients | 8192 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e3-zipf-sweep | wow | 601/s, p99 2.2 ms | 206/s, p99 23.4 ms | 1134/s, p99 5.9 ms | 1655/s, p99 9.9 ms | 2468/s, p99 18.3 ms | 2660/s, p99 61.2 ms | 2632/s, p99 153.6 ms | 2603/s, p99 348.2 ms | 2484/s, p99 905.2 ms | 2570/s, p99 1564.7 ms | 2567/s, p99 2883.6 ms | 2879/s, p99 5177.3 ms | 3398/s, p99 7143.4 ms | 3666/s, 695 errors |
| 1-axon-e3-zipf-sweep | axon | 514/s, p99 2.7 ms | 799/s, p99 4.3 ms | 1285/s, p99 5.6 ms | 1766/s, p99 12.0 ms | 1744/s, p99 39.9 ms | 1806/s, p99 93.2 ms | 1825/s, p99 197.6 ms | 1785/s, p99 405.5 ms | 1775/s, p99 794.6 ms | 1746/s, p99 1630.2 ms | 1803/s, p99 3211.3 ms | 1851/s, p99 5079.0 ms | 1863/s, p99 7045.1 ms | - |
| 2-axon-e3-zipf-sweep | axon | 511/s, p99 2.8 ms | 789/s, p99 5.0 ms | 1289/s, p99 5.6 ms | 1785/s, p99 12.4 ms | 1775/s, p99 38.4 ms | 1818/s, p99 89.6 ms | 1824/s, p99 196.6 ms | 1743/s, p99 434.2 ms | 1793/s, p99 774.1 ms | 1739/s, p99 1622.0 ms | 1777/s, p99 3227.6 ms | 1866/s, p99 5079.0 ms | 1878/s, p99 6946.8 ms | - |
| 2-wow-e3-zipf-sweep | wow | 604/s, p99 2.2 ms | 807/s, p99 3.8 ms | 1126/s, p99 6.0 ms | 1633/s, p99 10.8 ms | 2269/s, p99 21.4 ms | 2455/s, p99 69.6 ms | 2598/s, p99 156.7 ms | 2531/s, p99 354.3 ms | 2471/s, p99 901.1 ms | 2571/s, p99 1458.2 ms | 2552/s, p99 2932.7 ms | 2858/s, p99 5341.2 ms | 3458/s, p99 6979.6 ms | 3850/s, 454 errors |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| m5max-axon-contention@100k | 1-wow | store | **store does not match the runs** | transfers entered or refused: 552570 stored, 555013 expected |
| m5max-axon-contention@100k | 1-wow | store | **store does not match the runs** | entries confirmed: 548931 stored, 552570 expected |


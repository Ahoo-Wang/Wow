## axon-pg-contention@100k

Contention in the industry comparison: closed-loop sweeps to their peaks with Zipf keys (E1, E3) and the harness's hot keys (E1), Wow with no bus against Axon Framework 4, as axon-pg.

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×4 a step to 4096 and on while it rises, to 16384 at most; 2 round(s) of one session per arm, ABBA. Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed; every run on it first waited until the nodes were quiet.

Sessions, in order: 1-wow, 1-axon, 2-axon, 2-wow.

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow/axon from the lowest wow peak over the highest axon peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 zipf | ≥ 5175/s at 4096 (top step); ≥ 5008/s at 4096 (top step) | 5064/s at 1024; ≥ 4893/s at 4096 (top step) | ~ 0.99–1.06 | 3.5% | undetermined (both lower bounds) |
| E3 zipf | 1207/s at 64; 1216/s at 64 | 1188/s at 16; 1151/s at 64 | 1.02–1.06 | 3.2% | level (within the A/A spread) |
| E1 hot | 5196/s at 256; 4693/s at 1024 | 3019/s at 256; 3162/s at 256 | 1.48–1.72 | 10.7% | wow higher |

The load generator used at most 0.54 of its 2 CPUs at a peak; Kafka, on the same CPUs, was not measured (no open-loop run in this suite).

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 zipf | 4861/s at 256 (p99 152.6 ms); 4945/s at 256 (p99 155.6 ms) | 4492/s at 64 (p99 64.0 ms); 4247/s at 64 (p99 64.8 ms) | 1.08–1.16 | 5.8% | wow higher |
| E3 zipf | 1178/s at 16 (p99 37.1 ms); 1133/s at 16 (p99 36.9 ms) | 1188/s at 16 (p99 48.4 ms); 1130/s at 16 (p99 52.0 ms) | 0.95–1.04 | 5.2% | level (within the A/A spread) |
| E1 hot | 5196/s at 256 (p99 166.9 ms); 4664/s at 256 (p99 179.2 ms) | 2896/s at 64 (p99 64.0 ms); 3123/s at 64 (p99 57.6 ms) | 1.49–1.79 | 11.4% | wow higher |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 100000 (100000) | 2087342 (2086070–2302179) | 214121 (213466–214121) | 214121 (214121) | 214121 (214121) | 214121 (214121) | 0 (0) |
| 1-axon | 100000 (100000) | 1912983 (1912983–1912984) | 274268 (264996–274268) | 274268 (274268) | 274268 (274268) | 274268 (274268) | 0 (0) |
| 2-axon | 100000 (100000) | 1873911 (1873911–1873911) | 270836 (261668–270836) | 270836 (270836) | 270836 (270836) | 270836 (270836) | 0 (0) |
| 2-wow | 100000 (100000) | 2029106 (2028059–2239200) | 209114 (208049–209114) | 209114 (209114) | 209114 (209114) | 209114 (209114) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 2829705 | 3043826 | 1816.7 MB | 626 | 100000 | 38.4 MB | 402 | 1.1 MB |
| 1-axon | 3110055 | 3110055 | 1442.9 MB | 486 | 1552 | 1.1 MB | 718 | 0.6 MB |
| 2-axon | 3057255 | 3057255 | 1417.3 MB | 486 | 1506 | 1.0 MB | 707 | 0.5 MB |
| 2-wow | 2756448 | 2965562 | 1768.5 MB | 625 | 100000 | 39.7 MB | 416 | 1.3 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow | 8.4 s | 401.6 MB | 1335.4 MB |
| 1-axon | 4.3 s | 290.9 MB | 1325.1 MB |
| 2-axon | 3.5 s | 296.9 MB | 1319.5 MB |
| 2-wow | 5.6 s | 399.0 MB | 1341.8 MB |

The sweeps' steps (throughput, and p99 or the errors):

| Sweep | Arm | Peak | Stopped | 1 clients | 4 clients | 16 clients | 64 clients | 256 clients | 1024 clients | 4096 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-zipf-sweep | wow | ≥ 5175/s at 4096 (top step) | more than 1% failed | 1117/s, p99 2.8 ms | 1841/s, p99 4.9 ms | 2812/s, p99 16.6 ms | 4493/s, p99 40.4 ms | 4861/s, p99 152.6 ms | 4988/s, p99 1368.1 ms | 5175/s, 21556 errors |
| 1-wow-e3-zipf-sweep | wow | 1207/s at 64 | more than 1% failed | 517/s, p99 4.1 ms | 694/s, p99 13.5 ms | 1178/s, p99 37.1 ms | 1207/s, p99 415.7 ms | 1048/s, p99 2932.7 ms | 1008/s, 655 errors | - |
| 1-wow-e1-hot-sweep | wow | 5196/s at 256 | more than 1% failed | 1072/s, p99 2.8 ms | 1826/s, p99 4.8 ms | 3073/s, p99 14.0 ms | 4776/s, p99 44.8 ms | 5196/s, p99 166.9 ms | 4616/s, p99 684.0 ms | 3644/s, 149677 errors |
| 1-axon-e1-zipf-sweep | axon | 5064/s at 1024 | peaked below its top step | 1199/s, p99 2.4 ms | 2759/s, p99 3.6 ms | 4070/s, p99 15.7 ms | 4492/s, p99 64.0 ms | 4905/s, p99 301.1 ms | 5064/s, p99 460.8 ms | 4877/s, p99 1228.8 ms |
| 1-axon-e3-zipf-sweep | axon | 1188/s at 16 | more than 1% failed | 388/s, p99 5.7 ms | 876/s, p99 9.9 ms | 1188/s, p99 48.4 ms | 1111/s, p99 342.0 ms | 1104/s, p99 1564.7 ms | 1039/s, p99 5799.9 ms | 773/s, 7388 errors |
| 1-axon-e1-hot-sweep | axon | 3019/s at 256 | peaked below its top step | 1086/s, p99 2.8 ms | 2261/s, p99 4.4 ms | 2811/s, p99 17.7 ms | 2896/s, p99 64.0 ms | 3019/s, p99 270.3 ms | 2984/s, p99 565.2 ms | 2987/s, p99 1876.0 ms |
| 2-axon-e1-zipf-sweep | axon | ≥ 4893/s at 4096 (top step) | levelled off | 1008/s, p99 3.1 ms | 2531/s, p99 4.0 ms | 3499/s, p99 19.5 ms | 4247/s, p99 64.8 ms | 4786/s, p99 288.8 ms | 4725/s, p99 569.3 ms | 4893/s, p99 1220.6 ms |
| 2-axon-e3-zipf-sweep | axon | 1151/s at 64 | more than 1% failed | 369/s, p99 6.0 ms | 888/s, p99 9.2 ms | 1130/s, p99 52.0 ms | 1151/s, p99 319.5 ms | 1112/s, p99 1499.1 ms | 959/s, p99 6225.9 ms | 786/s, 6972 errors |
| 2-axon-e1-hot-sweep | axon | 3162/s at 256 | peaked below its top step | 1070/s, p99 2.7 ms | 2318/s, p99 4.2 ms | 2864/s, p99 17.8 ms | 3123/s, p99 57.6 ms | 3162/s, p99 270.3 ms | 3015/s, p99 708.6 ms | 3101/s, p99 1712.1 ms |
| 2-wow-e1-zipf-sweep | wow | ≥ 5008/s at 4096 (top step) | more than 1% failed | 1065/s, p99 2.8 ms | 1750/s, p99 5.1 ms | 2814/s, p99 15.4 ms | 4691/s, p99 37.9 ms | 4945/s, p99 155.6 ms | 4865/s, p99 1318.9 ms | 5008/s, 19727 errors |
| 2-wow-e3-zipf-sweep | wow | 1216/s at 64 | more than 1% failed | 512/s, p99 4.0 ms | 694/s, p99 11.7 ms | 1133/s, p99 36.9 ms | 1216/s, p99 358.4 ms | 1039/s, p99 2080.8 ms | 873/s, 1065 errors | - |
| 2-wow-e1-hot-sweep | wow | 4693/s at 1024 | more than 1% failed | 1060/s, p99 2.8 ms | 1726/s, p99 5.4 ms | 2980/s, p99 12.5 ms | 4281/s, p99 42.8 ms | 4664/s, p99 179.2 ms | 4693/s, p99 708.6 ms | 3908/s, 146033 errors |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |


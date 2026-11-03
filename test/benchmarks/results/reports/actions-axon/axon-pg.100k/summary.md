## axon-pg@100k

The industry comparison: Wow with no bus (wow.transport=none) against Axon Framework 4 on one PostgreSQL, one node each, as the local compare.sh runs it: per session E1 and E3 at one rate for both, closed-loop sweeps of E1 and E3 to their peaks, then each at half of its own sustainable rate.

E1 and E3, uniform keys over 100000 accounts; warm-up 20 s, measured 40 s in open loop, sweeps of 30 s steps after 10 s, the concurrency ×4 a step to 4096 and on while it rises, to 16384 at most; 2 round(s) of one session per arm, ABBA. Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: each session touched each of the 100000 accounts once after seeding, untimed (37–44 s); every run on it first waited until the nodes were quiet (3–16 s).

Sessions, in order: 1-wow, 1-axon, 2-axon, 2-wow.

**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; wow/axon from the lowest wow peak over the highest axon peak to the highest over the lowest. `≥` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 3971/s at 4096; 3824/s at 1024 | 3521/s at 256; 3746/s at 4096 | 1.02–1.13 | 6.4% | level (within the A/A spread) |
| E3 | 1549/s at 1024; 1413/s at 1024 | 665/s at 16; 746/s at 256 | 1.89–2.33 | 12.2% | wow higher |

The load generator used at most 0.51 of its 2 CPUs at a peak, and Kafka, idle on the same CPUs, at most 0.54 cores in an open-loop run: 1.06 cores together.

Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within 200 ms and no failure (`≥` at the sweep's top step).

| Sweep | wow | axon | wow/axon | A/A spread | Verdict |
| --- | --- | --- | --- | --- | --- |
| E1 | 3014/s at 64 (p99 103.4 ms); 3360/s at 256 (p99 196.6 ms) | 3140/s at 64 (p99 133.1 ms); 3293/s at 64 (p99 96.3 ms) | 0.92–1.07 | 11.5% | level (within the A/A spread) |
| E3 | 737/s at 16 (p99 165.9 ms); 627/s at 16 (p99 199.7 ms) | 665/s at 16 (p99 170.0 ms); 410/s at 4 (p99 171.0 ms) | 0.94–1.80 | 61.9% | level (within the A/A spread) |

**Same rate**: for the same work, whose latency and cost are lower. Both arms at one rate, before the sweeps (20 s warm-up, 40 s measured), in session order; kept up: at most 1% failed and at least 97% of the rate completed.

- E1 at 1000/s: 33% of the lowest sustainable rate a sweep found here (wow, 3014/s).
- E3 at 300/s: 73% of the lowest sustainable rate a sweep found here (axon, 410/s).

| Scenario | Metric | wow | axon |
| --- | --- | --- | --- |
| E1 | Rate | 1000/s, 1000/s | 1000/s, 1000/s |
| E1 | Throughput | 1000.0/s, 1000.0/s | 1000.0/s, 1000.0/s |
| E1 | Kept up | yes, yes | yes, yes |
| E1 | Failed | 0, 0 | 0, 0 |
| E1 | p50 | 17.54 ms, 3.71 ms | 1.19 ms, 1.51 ms |
| E1 | p99 | 364.5 ms, 376.8 ms | 130.6 ms, 344.1 ms |
| E1 | Node CPU ms/op | 0.559, 0.600 | 0.669, 0.599 |
| E1 | PostgreSQL CPU ms/op | 0.470, 0.512 | 0.694, 0.591 |
| E1 | PostgreSQL transactions/op | 0.98, 1.26 | 2.34, 2.07 |
| E1 | PostgreSQL WAL bytes/op | 1.3 KB, 1.3 KB | 660 B, 673 B |
| E1 | Node alloc/op | 78.4 KB, 79.2 KB | 81.9 KB, 80.5 KB |
| E1 | Node GC time | 0.4%, 0.4% | 0.8%, 0.3% |
| E1 | Node RSS max | 1067 MB, 1069 MB | 1231 MB, 1011 MB |
| E3 | Rate | 300/s, 300/s | 300/s, 300/s |
| E3 | Throughput | 300.0/s, 300.0/s | 300.0/s, 300.0/s |
| E3 | Kept up | yes, yes | yes, yes |
| E3 | Failed | 0, 0 | 0, 0 |
| E3 | p50 | 16.64 ms, 2.21 ms | 3.38 ms, 3.82 ms |
| E3 | p99 | 448.5 ms, 121.3 ms | 177.2 ms, 536.6 ms |
| E3 | Node CPU ms/op | 1.388, 1.977 | 1.955, 1.806 |
| E3 | PostgreSQL CPU ms/op | 1.376, 1.748 | 2.340, 2.131 |
| E3 | PostgreSQL transactions/op | 3.47, 5.60 | 7.12, 6.55 |
| E3 | PostgreSQL WAL bytes/op | 4.4 KB, 4.4 KB | 3.1 KB, 3.1 KB |
| E3 | Node alloc/op | 194.4 KB, 200.3 KB | 266.0 KB, 263.6 KB |
| E3 | Node GC time | 0.2%, 0.3% | 0.9%, 0.3% |
| E3 | Node RSS max | 1079 MB, 1081 MB | 1296 MB, 1017 MB |

**Own share**: with the same headroom, each one's latency and cost per command. Each arm at 50% of the sustainable rate its own sweep found in the session, after the sweeps, in session order.

| Scenario | Metric | wow | axon |
| --- | --- | --- | --- |
| E1 | Rate | 1507/s, 1680/s | 1570/s, 1647/s |
| E1 | Throughput | 1507.2/s, 1680.1/s | 1569.8/s, 1646.7/s |
| E1 | Kept up | yes, yes | yes, yes |
| E1 | Failed | 0, 0 | 0, 0 |
| E1 | p50 | 7.65 ms, 85.50 ms | 1.53 ms, 2.00 ms |
| E1 | p99 | 276.5 ms, 729.1 ms | 108.5 ms, 557.1 ms |
| E1 | Node CPU ms/op | 0.495, 0.384 | 0.520, 0.476 |
| E1 | PostgreSQL CPU ms/op | 0.442, 0.373 | 0.436, 0.378 |
| E1 | PostgreSQL transactions/op | 0.99, 0.37 | 1.71, 1.56 |
| E1 | PostgreSQL WAL bytes/op | 1.3 KB, 1.7 KB | 683 B, 1.1 KB |
| E1 | Node alloc/op | 81.4 KB, 78.2 KB | 79.0 KB, 78.2 KB |
| E1 | Node GC time | 0.8%, 0.8% | 0.4%, 0.4% |
| E1 | Node RSS max | 1338 MB, 1334 MB | 1314 MB, 1322 MB |
| E3 | Rate | 369/s, 313/s | 332/s, 205/s |
| E3 | Throughput | 368.6/s, 313.5/s | 332.3/s, 205.2/s |
| E3 | Kept up | yes, yes | yes, yes |
| E3 | Failed | 0, 0 | 0, 0 |
| E3 | p50 | 24.19 ms, 5.05 ms | 4.03 ms, 3.10 ms |
| E3 | p99 | 446.5 ms, 276.5 ms | 235.5 ms, 152.6 ms |
| E3 | Node CPU ms/op | 1.295, 1.684 | 1.658, 2.257 |
| E3 | PostgreSQL CPU ms/op | 1.242, 1.581 | 1.956, 3.059 |
| E3 | PostgreSQL transactions/op | 3.09, 4.32 | 6.14, 9.34 |
| E3 | PostgreSQL WAL bytes/op | 4.4 KB, 4.7 KB | 5.0 KB, 3.1 KB |
| E3 | Node alloc/op | 196.0 KB, 199.7 KB | 262.6 KB, 275.0 KB |
| E3 | Node GC time | 0.4%, 0.3% | 0.4%, 0.2% |
| E3 | Node RSS max | 1339 MB, 1334 MB | 1314 MB, 1322 MB |

**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).

| Session | accounts opened | deposits stored | transfers stored | transfer amounts locked | transfers entered or refused | entries confirmed | refusals unlocked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 100000 (100000) | 979567 (979567–1150081) | 272583 (264847–398221) | 272583 (272583) | 272583 (272583) | 272583 (272583) | 0 (0) |
| 1-axon | 100000 (100000) | 957890 (957890–957890) | 198344 (196794–198344) | 198344 (198344) | 198344 (198344) | 198344 (198344) | 0 (0) |
| 2-axon | 100000 (100000) | 1150992 (1150992–1150992) | 190755 (190708–190755) | 190755 (190755) | 190755 (190755) | 190755 (190755) | 0 (0) |
| 2-wow | 100000 (100000) | 963368 (963368–963368) | 251862 (243071–366878) | 251862 (251862) | 251862 (251862) | 251862 (251862) | 0 (0) |

**Storage**: the store's tables after each session, their indexes and TOAST included.

| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow | 1897316 | 2169899 | 1294.6 MB | 626 | 100000 | 42.0 MB | 441 | 1.0 MB |
| 1-axon | 1851266 | 1851266 | 850.7 MB | 482 | 0 | 0.0 MB | - | 0.3 MB |
| 2-axon | 2014012 | 2014012 | 916.9 MB | 477 | 0 | 0.0 MB | - | 0.5 MB |
| 2-wow | 1818954 | 2070816 | 1238.3 MB | 627 | 100000 | 41.9 MB | 439 | 1.0 MB |

**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.

| Session | Ready after | RSS at ready | RSS after the runs |
| --- | --- | --- | --- |
| 1-wow | 10.8 s | 407.7 MB | 1338.6 MB |
| 1-axon | 3.8 s | 285.7 MB | 1314.5 MB |
| 2-axon | 4.0 s | 289.4 MB | 1321.7 MB |
| 2-wow | 7.1 s | 401.3 MB | 1334.5 MB |

The sweeps' steps (throughput, and p99 or the errors):

| Sweep | Arm | Peak | Stopped | 1 clients | 4 clients | 16 clients | 64 clients | 256 clients | 1024 clients | 4096 clients | 16384 clients |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1-sweep | wow | 3971/s at 4096 | more than 1% failed | 503/s, p99 19.1 ms | 776/s, p99 80.9 ms | 1517/s, p99 108.5 ms | 3014/s, p99 103.4 ms | 3024/s, p99 317.4 ms | 3762/s, p99 569.3 ms | 3971/s, p99 1679.4 ms | 1321/s, 140842 errors |
| 1-wow-e3-sweep | wow | 1549/s at 1024 | more than 1% failed | 377/s, p99 12.5 ms | 530/s, p99 24.2 ms | 737/s, p99 165.9 ms | 777/s, p99 331.8 ms | 1160/s, p99 462.8 ms | 1549/s, p99 1032.2 ms | 487/s, 100717 errors | - |
| 1-axon-e1-sweep | axon | 3521/s at 256 | peaked below its top step | 702/s, p99 4.7 ms | 1441/s, p99 16.6 ms | 2292/s, p99 61.4 ms | 3140/s, p99 133.1 ms | 3521/s, p99 423.9 ms | 3316/s, p99 864.3 ms | 3116/s, p99 2441.2 ms | - |
| 1-axon-e3-sweep | axon | 665/s at 16 | more than 1% failed | 285/s, p99 10.6 ms | 443/s, p99 111.6 ms | 665/s, p99 170.0 ms | 662/s, p99 557.1 ms | 614/s, p99 2572.3 ms | 486/s, p99 6553.6 ms | 537/s, 1128 errors | - |
| 2-axon-e1-sweep | axon | 3746/s at 4096 | peaked below its top step | 798/s, p99 4.1 ms | 1922/s, p99 8.7 ms | 2466/s, p99 58.1 ms | 3293/s, p99 96.3 ms | 3476/s, p99 462.8 ms | 3462/s, p99 806.9 ms | 3746/s, p99 1744.9 ms | 2953/s, p99 6586.4 ms |
| 2-axon-e3-sweep | axon | 746/s at 256 | peaked below its top step | 232/s, p99 31.6 ms | 410/s, p99 171.0 ms | 364/s, p99 387.1 ms | 741/s, p99 401.4 ms | 746/s, p99 1294.3 ms | 697/s, p99 3129.3 ms | 677/s, p99 9109.5 ms | - |
| 2-wow-e1-sweep | wow | 3824/s at 1024 | peaked below its top step | 755/s, p99 6.8 ms | 1406/s, p99 10.4 ms | 1652/s, p99 100.9 ms | 2695/s, p99 150.5 ms | 3360/s, p99 196.6 ms | 3824/s, p99 544.8 ms | 3774/s, p99 1867.8 ms | - |
| 2-wow-e3-sweep | wow | 1413/s at 1024 | more than 1% failed | 207/s, p99 90.6 ms | 328/s, p99 178.2 ms | 627/s, p99 199.7 ms | 983/s, p99 255.0 ms | 1094/s, p99 643.1 ms | 1413/s, p99 1368.1 ms | 445/s, 96802 errors | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-wow-e1 | wow | 1000.0/s | 1000.0/s | 40000 | 0 | 17.5 ms | 201.7 ms | 364.5 ms | n/a | 528.4 ms | 0.56 cores | kafka 50% · postgres 47% | 0.26 of 2 | server 0.56 | 1.52 | 78.4 KB | - | - | - |
| 1-wow-e3 | wow | 300.0/s | 300.0/s | 12000 | 0 | 16.6 ms | 297.0 ms | 448.5 ms | n/a | 647.2 ms | 0.42 cores | kafka 45% · postgres 41% | 0.19 of 2 | kafka 0.45 | 4.25 | 194.4 KB | - | - | - |
| 1-wow-e1-own | wow | 1507.2/s | 1507.2/s | 60287 | 0 | 7.6 ms | 148.5 ms | 276.5 ms | n/a | 479.2 ms | 0.75 cores | kafka 45% · postgres 67% | 0.44 of 2 | server 0.75 | 1.24 | 81.4 KB | - | - | - |
| 1-wow-e3-own | wow | 368.6/s | 368.6/s | 14744 | 0 | 24.2 ms | 290.8 ms | 446.5 ms | n/a | 614.4 ms | 0.48 cores | kafka 44% · postgres 46% | 0.19 of 2 | server 0.48 | 3.73 | 196.0 KB | - | - | - |
| 1-axon-e1 | axon | 1000.0/s | 1000.0/s | 40000 | 0 | 1.2 ms | 53.2 ms | 130.6 ms | n/a | 407.6 ms | 0.67 cores | kafka 46% · postgres 69% | 0.34 of 2 | postgres 0.69 | 1.82 | 81.9 KB | - | - | - |
| 1-axon-e3 | axon | 300.0/s | 300.0/s | 12000 | 0 | 3.4 ms | 92.2 ms | 177.2 ms | n/a | 415.7 ms | 0.59 cores | kafka 45% · postgres 70% | 0.18 of 2 | postgres 0.70 | 5.81 | 266.0 KB | - | - | - |
| 1-axon-e1-own | axon | 1569.8/s | 1569.8/s | 62792 | 0 | 1.5 ms | 40.2 ms | 108.5 ms | n/a | 208.9 ms | 0.82 cores | kafka 47% · postgres 68% | 0.47 of 2 | server 0.82 | 1.25 | 79.0 KB | - | - | - |
| 1-axon-e3-own | axon | 332.3/s | 332.3/s | 13292 | 0 | 4.0 ms | 131.1 ms | 235.5 ms | n/a | 423.9 ms | 0.55 cores | kafka 44% · postgres 65% | 0.18 of 2 | postgres 0.65 | 4.95 | 262.6 KB | - | - | - |
| 2-axon-e1 | axon | 1000.0/s | 1000.0/s | 40000 | 0 | 1.5 ms | 172.0 ms | 344.1 ms | n/a | 696.3 ms | 0.60 cores | kafka 49% · postgres 59% | 0.30 of 2 | server 0.60 | 1.68 | 80.5 KB | - | - | - |
| 2-axon-e3 | axon | 300.0/s | 300.0/s | 12000 | 0 | 3.8 ms | 226.3 ms | 536.6 ms | n/a | 786.4 ms | 0.54 cores | kafka 46% · postgres 64% | 0.19 of 2 | postgres 0.64 | 5.48 | 263.6 KB | - | - | - |
| 2-axon-e1-own | axon | 1646.7/s | 1646.7/s | 65868 | 0 | 2.0 ms | 145.4 ms | 557.1 ms | n/a | 1048.6 ms | 0.78 cores | kafka 48% · postgres 62% | 0.47 of 2 | server 0.78 | 1.15 | 78.2 KB | - | - | - |
| 2-axon-e3-own | axon | 205.2/s | 205.2/s | 8208 | 0 | 3.1 ms | 69.6 ms | 152.6 ms | n/a | 214.0 ms | 0.46 cores | kafka 46% · postgres 63% | 0.19 of 2 | postgres 0.63 | 7.56 | 275.0 KB | - | - | - |
| 2-wow-e1 | wow | 1000.0/s | 1000.0/s | 40000 | 0 | 3.7 ms | 207.9 ms | 376.8 ms | n/a | 643.1 ms | 0.60 cores | kafka 54% · postgres 51% | 0.26 of 2 | server 0.60 | 1.66 | 79.2 KB | - | - | - |
| 2-wow-e3 | wow | 300.0/s | 300.0/s | 12000 | 0 | 2.2 ms | 38.9 ms | 121.3 ms | n/a | 206.8 ms | 0.59 cores | kafka 48% · postgres 52% | 0.20 of 2 | server 0.59 | 5.33 | 200.3 KB | - | - | - |
| 2-wow-e1-own | wow | 1680.1/s | 1680.1/s | 67204 | 0 | 85.5 ms | 452.6 ms | 729.1 ms | n/a | 1024.0 ms | 0.64 cores | kafka 47% · postgres 63% | 0.45 of 2 | server 0.64 | 1.04 | 78.2 KB | - | - | - |
| 2-wow-e3-own | wow | 313.5/s | 313.5/s | 12540 | 0 | 5.1 ms | 172.0 ms | 276.5 ms | n/a | 411.6 ms | 0.53 cores | kafka 46% · postgres 50% | 0.18 of 2 | server 0.53 | 4.75 | 199.7 KB | - | - | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| axon-pg@100k | 1-wow-e1 | steady | **unstable** | second half against first, beyond the default limits: p50 -55.0% (±25.0%) |
| axon-pg@100k | 1-wow-e1 | steady | **p99 > 10× p50** | p50 17.5 ms, p99 364.5 ms |
| axon-pg@100k | 1-wow-e3 | steady | **unstable** | second half against first, beyond the default limits: p50 +643.9% (±25.0%) |
| axon-pg@100k | 1-wow-e3 | steady | **p99 > 10× p50** | p50 16.6 ms, p99 448.5 ms |
| axon-pg@100k | 1-wow-e1-own | steady | **unstable** | second half against first, beyond the default limits: p50 +101.3% (±25.0%) |
| axon-pg@100k | 1-wow-e1-own | steady | **p99 > 10× p50** | p50 7.6 ms, p99 276.5 ms |
| axon-pg@100k | 1-wow-e3-own | steady | **unstable** | second half against first, beyond the default limits: p50 +222.3% (±25.0%) |
| axon-pg@100k | 1-wow-e3-own | steady | **p99 > 10× p50** | p50 24.2 ms, p99 446.5 ms |
| axon-pg@100k | 1-axon-e1 | steady | **p99 > 10× p50** | p50 1.2 ms, p99 130.6 ms |
| axon-pg@100k | 1-axon-e3 | steady | **unstable** | second half against first, beyond the default limits: p50 +33.7% (±25.0%) |
| axon-pg@100k | 1-axon-e3 | steady | **p99 > 10× p50** | p50 3.4 ms, p99 177.2 ms |
| axon-pg@100k | 1-axon-e1-own | steady | **p99 > 10× p50** | p50 1.5 ms, p99 108.5 ms |
| axon-pg@100k | 1-axon-e3-own | steady | **p99 > 10× p50** | p50 4.0 ms, p99 235.5 ms |
| axon-pg@100k | 2-axon-e1 | steady | **unstable** | second half against first, beyond the default limits: p50 +61.2% (±25.0%) |
| axon-pg@100k | 2-axon-e1 | steady | **p99 > 10× p50** | p50 1.5 ms, p99 344.1 ms |
| axon-pg@100k | 2-axon-e3 | steady | **p99 > 10× p50** | p50 3.8 ms, p99 536.6 ms |
| axon-pg@100k | 2-axon-e1-own | steady | **p99 > 10× p50** | p50 2.0 ms, p99 557.1 ms |
| axon-pg@100k | 2-axon-e3-own | steady | **p99 > 10× p50** | p50 3.1 ms, p99 152.6 ms |
| axon-pg@100k | 2-wow-e1 | steady | **unstable** | second half against first, beyond the default limits: p50 +374.9% (±25.0%) |
| axon-pg@100k | 2-wow-e1 | steady | **p99 > 10× p50** | p50 3.7 ms, p99 376.8 ms |
| axon-pg@100k | 2-wow-e3 | steady | **p99 > 10× p50** | p50 2.2 ms, p99 121.3 ms |
| axon-pg@100k | 2-wow-e1-own | steady | **unstable** | second half against first, beyond the default limits: p50 -53.7% (±25.0%) |
| axon-pg@100k | 2-wow-e3-own | steady | **unstable** | second half against first, beyond the default limits: p50 -64.3% (±25.0%) |
| axon-pg@100k | 2-wow-e3-own | steady | **p99 > 10× p50** | p50 5.1 ms, p99 276.5 ms |


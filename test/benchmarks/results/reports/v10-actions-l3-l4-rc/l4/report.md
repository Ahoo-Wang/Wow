# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `main` 4aa5e0d607c20bb7901973f5f2a7033c8d92c512; V10 baseline: none; harness and load generator from head.

Tier: L4; coverage: cold-mongo@1M, cold-pg@1M, sustainable-mongo@100k, sustainable-pg@100k, e5-mongo@100k, e5-pg@100k, cliff-e3-pg@100k, cliff-e3-mongo@100k.

Each suite ran on one runner of its own, so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cold-mongo@1M | d1-head-e1-200 | steady | **p99 > 10× p50** | p50 3.6 ms, p99 68.1 ms |
| e5-mongo@100k | e1-steady | steady | **p99 > 10× p50** | p50 3.7 ms, p99 52.7 ms |
| e5-mongo@100k | e1-handover | hand-over | **tail read the store** | gap=17 |
| e5-mongo@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +159.5% (±25.0%) |
| e5-mongo@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 6.1 ms, p99 1400.8 ms |
| e5-mongo@100k | e3-steady | steady | **p99 > 10× p50** | p50 8.2 ms, p99 147.5 ms |
| e5-mongo@100k | e3-handover | hand-over | **tail read the store** | gap=20 |
| e5-mongo@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +68.2% (±25.0%) |
| e5-mongo@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 13.4 ms, p99 3375.1 ms |
| e5-pg@100k | e1-handover | hand-over | **tail read the store** | gap=6 |
| e5-pg@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +71.1% (±25.0%) |
| e5-pg@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 2.9 ms, p99 71.7 ms |
| e5-pg@100k | e3-handover | hand-over | **tail read the store** | gap=20 |
| e5-pg@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +35.7% (±25.0%) |
| e5-pg@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 7.2 ms, p99 90.1 ms |
| cliff-e3-pg@100k | s1-50 | steady | **unstable** | second half against first, beyond the default limits: p50 -25.7% (±25.0%) |
| cliff-e3-pg@100k | s5-400 | steady | **unstable** | second half against first, beyond the default limits: p50 -27.0% (±25.0%) |
| cliff-e3-pg@100k | s5-400 | steady | **p99 > 10× p50** | p50 4.1 ms, p99 59.9 ms |
| cliff-e3-mongo@100k | s3-200 | steady | **p99 > 10× p50** | p50 4.7 ms, p99 60.4 ms |
| cliff-e3-mongo@100k | s4-300 | steady | **p99 > 10× p50** | p50 4.4 ms, p99 110.1 ms |
| cliff-e3-mongo@100k | s5-400 | steady | **p99 > 10× p50** | p50 9.3 ms, p99 166.9 ms |
| cliff-e3-mongo@100k | s6-500 | steady | **p99 > 10× p50** | p50 9.9 ms, p99 182.3 ms |

## Sustainable rates (capacity guidance)

### sustainable-mongo@100k

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **1151.7/s** | 12.9 ms | 99.3 ms | 2.37 | 2047.5/s | 1151.7–1215.7/s |
| E2 | **547.4/s** | 14.1 ms | 191.5 ms | 4.74 | 1094.8/s | 547.4–581.6/s |
| E3 | **362.9/s** | 13.4 ms | 152.6 ms | 7.54 | 893.3/s | 362.9–390.8/s |

### sustainable-pg@100k

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **1164.7/s** | 4.4 ms | 83.5 ms | 2.03 | 2484.8/s | 1164.7–1242.4/s |
| E3 | **751.1/s** | 17.8 ms | 199.7 ms | 3.53 | 1602.4/s | 751.1–801.2/s |

## E5 cluster: steady and hand-over

### e5-mongo@100k

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 3.7 ms | 52.7 ms | 204.8 ms | 0 | 150/s | - | tailRead idle=6 |
| e1-handover | 150.0/s | 150.0/s | 0 | 6.1 ms | 1400.8 ms | 4358.1 ms | 0 | 150/s | - | forwardFailed local=16 · tailRead gap=17,idle=20,start=35 |
| e3-steady | 50.0/s | 50.0/s | 0 | 8.2 ms | 147.5 ms | 449.1 ms | 0 | 50/s | - | tailRead idle=14 |
| e3-handover | 50.0/s | 50.0/s | 4 | 13.4 ms | 3375.1 ms | 6715.6 ms | 0 | 48/s | http-504-wow.wait.timeout 3 · exception-IOException 1 | forwardFailed local=5 · tailRead gap=20,idle=23,start=30 · waitTimedOut COMMITTED=3 |

### e5-pg@100k

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 2.6 ms | 12.7 ms | 116.7 ms | 0 | 150/s | - | tailRead idle=9 |
| e1-handover | 150.0/s | 150.0/s | 0 | 2.9 ms | 71.7 ms | 757.8 ms | 0 | 150/s | - | forwardFailed local=8 · tailRead gap=6,idle=8,start=34 |
| e3-steady | 50.0/s | 50.0/s | 0 | 6.2 ms | 36.6 ms | 238.2 ms | 0 | 50/s | - | tailRead idle=6 |
| e3-handover | 50.0/s | 50.0/s | 0 | 7.2 ms | 90.1 ms | 1204.5 ms | 0 | 50/s | - | forwardFailed local=12 · tailRead gap=20,idle=9,start=20 |

## Runners

| Suite | CPU | Cores | Memory |
| --- | --- | --- | --- |
| cold-mongo@1M | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| cold-pg@1M | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB |
| sustainable-mongo@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| sustainable-pg@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| e5-mongo@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| e5-pg@100k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB |
| cliff-e3-pg@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| cliff-e3-mongo@100k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |

## E3 cliff steps

Fixed open-loop steps, a minute each on fresh containers. "Kept up": at most 1% failed and at least 97% of the rate completed. V10 signals count from the server start (seeding included): `tailRead` reasons other than `start` mean the tails fell back to the store, `refused wow.command.busy` that the node shed load.

### cliff-e3-pg@100k

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 3.5 ms | 9.5 ms | 32.0 ms | - | tailRead idle=2 |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 3.6 ms | 12.3 ms | 60.7 ms | - | tailRead idle=1 |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 2.8 ms | 38.4 ms | 274.4 ms | - | tailRead idle=1 |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 2.8 ms | 27.3 ms | 127.0 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 4.1 ms | 59.9 ms | 256.0 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 7.1 ms | 64.5 ms | 213.0 ms | - | none |

### cliff-e3-mongo@100k

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 4.5 ms | 17.2 ms | 95.0 ms | - | tailRead idle=3 |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 5.2 ms | 34.6 ms | 103.3 ms | - | tailRead idle=1 |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 4.7 ms | 60.4 ms | 141.3 ms | - | none |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 4.4 ms | 110.1 ms | 219.1 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 9.3 ms | 166.9 ms | 383.0 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 9.9 ms | 182.3 ms | 301.1 ms | - | none |

---

### cold-mongo@1M

A million accounts on MongoDB, uniform keys, so the caches miss (cold): E1 writes and E4 point reads at fixed rates.

scenarios below, uniform keys over 1000000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Cold: not primed on purpose; the caches start empty and the keys are uniform over 1000000 accounts.
The accounts exceed the node's state cache (a tenth of the 1 GB heap, about 104857 states of few requests): the steady state is a full cache with evictions.

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| d1-head-e1-200 | head | 200.0/s | 200.0/s | 8000 | 0 | 3.6 ms | 21.5 ms | 68.1 ms | n/a | 240.6 ms | 0.41 cores | kafka 51% · mongo 95% | 0.18 of 1 | mongo 0.95 | 9.36 | 234.1 KB | - |
| d2-head-e4-state-500 | head | 500.0/s | 500.0/s | 20000 | 0 | 0.9 ms | 2.5 ms | 6.2 ms | n/a | 108.5 ms | 0.25 cores | kafka 53% · mongo 43% | 0.25 of 1 | kafka 0.53 | 2.44 | 71.1 KB | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cold-mongo@1M | d1-head-e1-200 | steady | **p99 > 10× p50** | p50 3.6 ms, p99 68.1 ms |


### cold-pg@1M

A million accounts on PostgreSQL, as cold-mongo.

scenarios below, uniform keys over 1000000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Cold: not primed on purpose; the caches start empty and the keys are uniform over 1000000 accounts.
The accounts exceed the node's state cache (a tenth of the 1 GB heap, about 104857 states of few requests): the steady state is a full cache with evictions.

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| d1-head-e1-200 | head | 200.0/s | 200.0/s | 8000 | 0 | 1.4 ms | 4.4 ms | 16.8 ms | n/a | 78.3 ms | 0.22 cores | kafka 41% · postgres 16% | 0.12 of 1 | kafka 0.41 | 3.98 | 113.2 KB | - |
| d2-head-e4-state-500 | head | 500.0/s | 500.0/s | 20000 | 0 | 0.4 ms | 1.3 ms | 2.5 ms | n/a | 4.2 ms | 0.11 cores | kafka 34% · postgres 6% | 0.15 of 1 | kafka 0.34 | 1.01 | 33.2 KB | - |


### sustainable-mongo@100k

Capacity guidance on MongoDB: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (55–59 s), then waited until the nodes were quiet again (3–5 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| head | **missing** | - | -/s | - |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-probe1 | head | 1023.8/s | 1023.8/s | 30714 | 0 | 8.6 ms | 72.2 ms | 161.8 ms | n/a | 290.8 ms | 0.80 cores | kafka 49% · mongo 141% | 0.53 of 1 | mongo 1.41 | 2.63 | 116.8 KB | - |
| e1-probe2 | head | 1535.6/s | 1474.2/s | 44226 | 1842 | 12451.8 ms | 13893.6 ms | 15794.2 ms | n/a | 16449.5 ms | 0.80 cores | kafka 50% · mongo 127% | 0.95 of 1 | mongo 1.27 | 1.74 | 90.3 KB | generator-overload 1842 |
| e1-probe3 | head | 1279.7/s | 1279.7/s | 38391 | 0 | 1019.9 ms | 4079.6 ms | 4423.7 ms | n/a | 4516.2 ms | 0.82 cores | kafka 50% · mongo 133% | 0.86 of 1 | mongo 1.33 | 2.07 | 107.2 KB | - |
| e1-probe4 | head | 1151.7/s | 1151.7/s | 34550 | 0 | 12.9 ms | 60.7 ms | 99.3 ms | n/a | 344.1 ms | 0.83 cores | kafka 49% · mongo 141% | 0.60 of 1 | mongo 1.41 | 2.37 | 109.0 KB | - |
| e1-probe5 | head | 1215.7/s | 1215.7/s | 36471 | 0 | 1011.7 ms | 4849.7 ms | 5505.0 ms | n/a | 5636.1 ms | 0.83 cores | kafka 49% · mongo 133% | 0.85 of 1 | mongo 1.33 | 2.18 | 110.8 KB | - |
| e2-probe1 | head | 547.4/s | 547.4/s | 16422 | 0 | 14.1 ms | 95.7 ms | 191.5 ms | n/a | 294.9 ms | 0.79 cores | kafka 54% · mongo 127% | 0.53 of 1 | mongo 1.27 | 4.74 | 204.4 KB | - |
| e2-probe2 | head | 821.1/s | 821.1/s | 24633 | 0 | 17956.9 ms | 19267.6 ms | 21102.6 ms | n/a | 22406.3 ms | 0.77 cores | kafka 49% · mongo 114% | 0.94 of 1 | mongo 1.14 | 2.91 | 170.4 KB | - |
| e2-probe3 | head | 684.2/s | 684.2/s | 20525 | 0 | 101.9 ms | 4014.1 ms | 4587.5 ms | n/a | 5079.0 ms | 0.86 cores | kafka 51% · mongo 123% | 0.77 of 1 | mongo 1.23 | 3.80 | 198.9 KB | - |
| e2-probe4 | head | 615.8/s | 615.8/s | 18474 | 0 | 30.7 ms | 2129.9 ms | 2588.7 ms | n/a | 2768.9 ms | 0.81 cores | kafka 47% · mongo 126% | 0.66 of 1 | mongo 1.26 | 4.13 | 201.2 KB | - |
| e2-probe5 | head | 581.6/s | 581.6/s | 17448 | 0 | 25.2 ms | 356.4 ms | 1056.8 ms | n/a | 1171.5 ms | 0.83 cores | kafka 52% · mongo 126% | 0.61 of 1 | mongo 1.26 | 4.50 | 199.7 KB | - |
| e3-probe1 | head | 446.6/s | 446.6/s | 13397 | 0 | 16.8 ms | 195.6 ms | 288.8 ms | n/a | 385.0 ms | 0.76 cores | kafka 49% · mongo 152% | 0.28 of 1 | mongo 1.52 | 6.20 | 285.6 KB | - |
| e3-probe2 | head | 223.3/s | 223.3/s | 6698 | 0 | 6.5 ms | 37.6 ms | 93.7 ms | n/a | 192.5 ms | 0.64 cores | kafka 53% · mongo 129% | 0.22 of 1 | mongo 1.29 | 11.01 | 367.2 KB | - |
| e3-probe3 | head | 335.0/s | 335.0/s | 10050 | 0 | 12.4 ms | 77.8 ms | 198.7 ms | n/a | 333.8 ms | 0.74 cores | kafka 52% · mongo 148% | 0.26 of 1 | mongo 1.48 | 8.15 | 326.7 KB | - |
| e3-probe4 | head | 390.8/s | 390.8/s | 11724 | 0 | 14.6 ms | 107.0 ms | 214.0 ms | n/a | 479.2 ms | 0.74 cores | kafka 49% · mongo 150% | 0.28 of 1 | mongo 1.50 | 6.98 | 305.5 KB | - |
| e3-probe5 | head | 362.9/s | 362.9/s | 10887 | 0 | 13.4 ms | 88.6 ms | 152.6 ms | n/a | 215.0 ms | 0.75 cores | kafka 50% · mongo 149% | 0.30 of 1 | mongo 1.49 | 7.54 | 313.6 KB | - |

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **1151.7/s** | 12.9 ms | 99.3 ms | 2.37 | 2047.5/s | 1151.7–1215.7/s |
| E2 | **547.4/s** | 14.1 ms | 191.5 ms | 4.74 | 1094.8/s | 547.4–581.6/s |
| E3 | **362.9/s** | 13.4 ms | 152.6 ms | 7.54 | 893.3/s | 362.9–390.8/s |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |


### sustainable-pg@100k

Capacity guidance on PostgreSQL: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (51–53 s), then waited until the nodes were quiet again (4–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| head | **missing** | - | -/s | - |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-probe1 | head | 1242.4/s | 1242.4/s | 37271 | 0 | 11.9 ms | 2605.1 ms | 3080.2 ms | n/a | 3178.5 ms | 0.98 cores | kafka 55% · postgres 80% | 0.77 of 1 | server 0.98 | 1.87 | 90.7 KB | - |
| e1-probe2 | head | 621.2/s | 621.2/s | 18635 | 0 | 1.5 ms | 4.8 ms | 11.0 ms | n/a | 57.3 ms | 0.72 cores | kafka 56% · postgres 49% | 0.32 of 1 | server 0.72 | 2.84 | 90.3 KB | - |
| e1-probe3 | head | 931.8/s | 931.8/s | 27954 | 0 | 2.4 ms | 9.4 ms | 19.8 ms | n/a | 64.8 ms | 0.95 cores | kafka 56% · postgres 70% | 0.48 of 1 | server 0.95 | 2.37 | 89.4 KB | - |
| e1-probe4 | head | 1087.1/s | 1087.1/s | 32612 | 0 | 3.9 ms | 18.2 ms | 49.7 ms | n/a | 155.6 ms | 1.00 cores | kafka 50% · postgres 79% | 0.57 of 1 | server 1.00 | 2.10 | 88.5 KB | - |
| e1-probe5 | head | 1164.7/s | 1164.7/s | 34940 | 0 | 4.4 ms | 28.4 ms | 83.5 ms | n/a | 182.3 ms | 1.01 cores | kafka 56% · postgres 79% | 0.61 of 1 | server 1.01 | 2.03 | 88.2 KB | - |
| e3-probe1 | head | 801.2/s | 801.2/s | 24036 | 0 | 20.0 ms | 207.9 ms | 346.1 ms | n/a | 430.1 ms | 1.04 cores | kafka 52% · postgres 112% | 0.43 of 1 | postgres 1.12 | 3.34 | 204.9 KB | - |
| e3-probe2 | head | 400.6/s | 400.6/s | 12018 | 0 | 4.3 ms | 19.3 ms | 34.0 ms | n/a | 73.7 ms | 0.94 cores | kafka 54% · postgres 88% | 0.26 of 1 | server 0.94 | 5.89 | 222.1 KB | - |
| e3-probe3 | head | 600.9/s | 600.9/s | 18027 | 0 | 10.8 ms | 43.3 ms | 81.4 ms | n/a | 234.5 ms | 1.02 cores | kafka 54% · postgres 108% | 0.34 of 1 | postgres 1.08 | 4.39 | 220.7 KB | - |
| e3-probe4 | head | 701.0/s | 701.0/s | 21030 | 0 | 14.6 ms | 75.8 ms | 143.4 ms | n/a | 237.6 ms | 1.05 cores | kafka 58% · postgres 108% | 0.37 of 1 | postgres 1.08 | 3.86 | 211.5 KB | - |
| e3-probe5 | head | 751.1/s | 751.1/s | 22533 | 0 | 17.8 ms | 102.4 ms | 199.7 ms | n/a | 282.6 ms | 1.04 cores | kafka 50% · postgres 111% | 0.41 of 1 | postgres 1.11 | 3.53 | 207.8 KB | - |

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **1164.7/s** | 4.4 ms | 83.5 ms | 2.03 | 2484.8/s | 1164.7–1242.4/s |
| E3 | **751.1/s** | 17.8 ms | 199.7 ms | 3.53 | 1602.4/s | 751.1–801.2/s |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |


### e5-mongo@100k

E5 cluster of three nodes on MongoDB, requests round-robin: E1 and E3 steady, then with one node stopped and restarted mid-measurement (its partitions handed over and back).

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (110–118 s), then waited until the nodes were quiet again (4–4 s).

Three nodes, requests round-robin, 90 s measured; in the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later.

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 3.7 ms | 52.7 ms | 204.8 ms | 0 | 150/s | - | tailRead idle=6 |
| e1-handover | 150.0/s | 150.0/s | 0 | 6.1 ms | 1400.8 ms | 4358.1 ms | 0 | 150/s | - | forwardFailed local=16 · tailRead gap=17,idle=20,start=35 |
| e3-steady | 50.0/s | 50.0/s | 0 | 8.2 ms | 147.5 ms | 449.1 ms | 0 | 50/s | - | tailRead idle=14 |
| e3-handover | 50.0/s | 50.0/s | 4 | 13.4 ms | 3375.1 ms | 6715.6 ms | 0 | 48/s | http-504-wow.wait.timeout 3 · exception-IOException 1 | forwardFailed local=5 · tailRead gap=20,idle=23,start=30 · waitTimedOut COMMITTED=3 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | head | 150.0/s | 150.0/s | 13500 | 0 | 3.7 ms | 12.4 ms | 52.7 ms | n/a | 204.8 ms | 0.54 cores | kafka 57% · mongo 64% | 0.15 of 1 | mongo 0.64 | 11.64 | 234.4 KB | - |
| e1-handover | head | 150.0/s | 150.0/s | 13500 | 0 | 6.1 ms | 123.9 ms | 1400.8 ms | n/a | 4358.1 ms | - | kafka 54% · mongo 72% | 0.21 of 1 | - | - | - | - |
| e3-steady | head | 50.0/s | 50.0/s | 4500 | 0 | 8.2 ms | 38.7 ms | 147.5 ms | n/a | 449.1 ms | 0.66 cores | kafka 52% · mongo 61% | 0.09 of 1 | server 0.66 | 35.80 | 680.4 KB | - |
| e3-handover | head | 50.0/s | 50.0/s | 4496 | 4 | 13.4 ms | 297.0 ms | 3375.1 ms | n/a | 6715.6 ms | - | kafka 52% · mongo 66% | 0.11 of 1 | - | - | - | http-504-wow.wait.timeout 3 · exception-IOException 1 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e5-mongo@100k | e1-steady | steady | **p99 > 10× p50** | p50 3.7 ms, p99 52.7 ms |
| e5-mongo@100k | e1-handover | hand-over | **tail read the store** | gap=17 |
| e5-mongo@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +159.5% (±25.0%) |
| e5-mongo@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 6.1 ms, p99 1400.8 ms |
| e5-mongo@100k | e3-steady | steady | **p99 > 10× p50** | p50 8.2 ms, p99 147.5 ms |
| e5-mongo@100k | e3-handover | hand-over | **tail read the store** | gap=20 |
| e5-mongo@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +68.2% (±25.0%) |
| e5-mongo@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 13.4 ms, p99 3375.1 ms |


### e5-pg@100k

E5 cluster of three nodes on PostgreSQL, as e5-mongo.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (68–78 s), then waited until the nodes were quiet again (3–4 s).

Three nodes, requests round-robin, 90 s measured; in the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later.

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 2.6 ms | 12.7 ms | 116.7 ms | 0 | 150/s | - | tailRead idle=9 |
| e1-handover | 150.0/s | 150.0/s | 0 | 2.9 ms | 71.7 ms | 757.8 ms | 0 | 150/s | - | forwardFailed local=8 · tailRead gap=6,idle=8,start=34 |
| e3-steady | 50.0/s | 50.0/s | 0 | 6.2 ms | 36.6 ms | 238.2 ms | 0 | 50/s | - | tailRead idle=6 |
| e3-handover | 50.0/s | 50.0/s | 0 | 7.2 ms | 90.1 ms | 1204.5 ms | 0 | 50/s | - | forwardFailed local=12 · tailRead gap=20,idle=9,start=20 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | head | 150.0/s | 150.0/s | 13500 | 0 | 2.6 ms | 6.0 ms | 12.7 ms | n/a | 116.7 ms | 0.48 cores | kafka 41% · postgres 12% | 0.12 of 1 | server 0.48 | 6.76 | 161.2 KB | - |
| e1-handover | head | 150.0/s | 150.0/s | 13500 | 0 | 2.9 ms | 16.6 ms | 71.7 ms | n/a | 757.8 ms | - | kafka 40% · postgres 14% | 0.12 of 1 | - | - | - | - |
| e3-steady | head | 50.0/s | 50.0/s | 4500 | 0 | 6.2 ms | 13.9 ms | 36.6 ms | n/a | 238.2 ms | 0.57 cores | kafka 42% · postgres 13% | 0.08 of 1 | server 0.57 | 22.41 | 451.5 KB | - |
| e3-handover | head | 50.0/s | 50.0/s | 4500 | 0 | 7.2 ms | 26.1 ms | 90.1 ms | n/a | 1204.5 ms | - | kafka 40% · postgres 14% | 0.08 of 1 | - | - | - | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e5-pg@100k | e1-handover | hand-over | **tail read the store** | gap=6 |
| e5-pg@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +71.1% (±25.0%) |
| e5-pg@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 2.9 ms, p99 71.7 ms |
| e5-pg@100k | e3-handover | hand-over | **tail read the store** | gap=20 |
| e5-pg@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +35.7% (±25.0%) |
| e5-pg@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 7.2 ms, p99 90.1 ms |


### cliff-e3-pg@100k

E3 on PostgreSQL in fixed open-loop steps: where completions stop following the rate, and whether the tails fell back to the store (a cliff) or the node refused work (overload).

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (51–54 s), then waited until the nodes were quiet again (4–4 s).

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 3.5 ms | 9.5 ms | 32.0 ms | - | tailRead idle=2 |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 3.6 ms | 12.3 ms | 60.7 ms | - | tailRead idle=1 |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 2.8 ms | 38.4 ms | 274.4 ms | - | tailRead idle=1 |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 2.8 ms | 27.3 ms | 127.0 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 4.1 ms | 59.9 ms | 256.0 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 7.1 ms | 64.5 ms | 213.0 ms | - | none |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | head | 50.0/s | 50.0/s | 3000 | 0 | 3.5 ms | 6.9 ms | 9.5 ms | n/a | 32.0 ms | 0.21 cores | kafka 55% · postgres 17% | 0.09 of 1 | kafka 0.55 | 18.48 | 265.8 KB | - |
| s2-100 | head | 100.0/s | 100.0/s | 6000 | 0 | 3.6 ms | 6.9 ms | 12.3 ms | n/a | 60.7 ms | 0.41 cores | kafka 61% · postgres 29% | 0.17 of 1 | kafka 0.61 | 13.05 | 246.6 KB | - |
| s3-200 | head | 200.0/s | 200.0/s | 12000 | 0 | 2.8 ms | 7.7 ms | 38.4 ms | n/a | 274.4 ms | 0.65 cores | kafka 60% · postgres 49% | 0.23 of 1 | server 0.65 | 8.72 | 232.5 KB | - |
| s4-300 | head | 300.0/s | 300.0/s | 18000 | 0 | 2.8 ms | 9.7 ms | 27.3 ms | n/a | 127.0 ms | 0.80 cores | kafka 59% · postgres 66% | 0.26 of 1 | server 0.80 | 6.84 | 224.6 KB | - |
| s5-400 | head | 400.0/s | 400.0/s | 24000 | 0 | 4.1 ms | 17.9 ms | 59.9 ms | n/a | 256.0 ms | 0.95 cores | kafka 54% · postgres 83% | 0.31 of 1 | server 0.95 | 5.80 | 223.3 KB | - |
| s6-500 | head | 500.0/s | 500.0/s | 30000 | 0 | 7.1 ms | 25.2 ms | 64.5 ms | n/a | 213.0 ms | 1.02 cores | kafka 55% · postgres 98% | 0.36 of 1 | server 1.02 | 5.10 | 222.1 KB | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cliff-e3-pg@100k | s1-50 | steady | **unstable** | second half against first, beyond the default limits: p50 -25.7% (±25.0%) |
| cliff-e3-pg@100k | s5-400 | steady | **unstable** | second half against first, beyond the default limits: p50 -27.0% (±25.0%) |
| cliff-e3-pg@100k | s5-400 | steady | **p99 > 10× p50** | p50 4.1 ms, p99 59.9 ms |


### cliff-e3-mongo@100k

E3 on MongoDB in fixed open-loop steps, as cliff-e3-pg.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (34–38 s), then waited until the nodes were quiet again (3–4 s).

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 4.5 ms | 17.2 ms | 95.0 ms | - | tailRead idle=3 |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 5.2 ms | 34.6 ms | 103.3 ms | - | tailRead idle=1 |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 4.7 ms | 60.4 ms | 141.3 ms | - | none |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 4.4 ms | 110.1 ms | 219.1 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 9.3 ms | 166.9 ms | 383.0 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 9.9 ms | 182.3 ms | 301.1 ms | - | none |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | head | 50.0/s | 50.0/s | 3000 | 0 | 4.5 ms | 9.2 ms | 17.2 ms | n/a | 95.0 ms | 0.16 cores | kafka 39% · mongo 39% | 0.07 of 1 | kafka 0.39 | 18.82 | 477.7 KB | - |
| s2-100 | head | 100.0/s | 100.0/s | 6000 | 0 | 5.2 ms | 12.5 ms | 34.6 ms | n/a | 103.3 ms | 0.33 cores | kafka 45% · mongo 64% | 0.14 of 1 | mongo 0.64 | 14.20 | 426.0 KB | - |
| s3-200 | head | 200.0/s | 200.0/s | 12000 | 0 | 4.7 ms | 23.6 ms | 60.4 ms | n/a | 141.3 ms | 0.54 cores | kafka 49% · mongo 95% | 0.22 of 1 | mongo 0.95 | 9.86 | 386.5 KB | - |
| s4-300 | head | 300.0/s | 300.0/s | 18000 | 0 | 4.4 ms | 33.0 ms | 110.1 ms | n/a | 219.1 ms | 0.55 cores | kafka 44% · mongo 110% | 0.20 of 1 | mongo 1.10 | 6.96 | 365.2 KB | - |
| s5-400 | head | 400.0/s | 400.0/s | 24000 | 0 | 9.3 ms | 75.3 ms | 166.9 ms | n/a | 383.0 ms | 0.64 cores | kafka 42% · mongo 116% | 0.27 of 1 | mongo 1.16 | 5.55 | 322.3 KB | - |
| s6-500 | head | 500.0/s | 500.0/s | 30000 | 0 | 9.9 ms | 74.8 ms | 182.3 ms | n/a | 301.1 ms | 0.68 cores | kafka 46% · mongo 134% | 0.29 of 1 | mongo 1.34 | 4.95 | 309.8 KB | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cliff-e3-mongo@100k | s3-200 | steady | **p99 > 10× p50** | p50 4.7 ms, p99 60.4 ms |
| cliff-e3-mongo@100k | s4-300 | steady | **p99 > 10× p50** | p50 4.4 ms, p99 110.1 ms |
| cliff-e3-mongo@100k | s5-400 | steady | **p99 > 10× p50** | p50 9.3 ms, p99 166.9 ms |
| cliff-e3-mongo@100k | s6-500 | steady | **p99 > 10× p50** | p50 9.9 ms, p99 182.3 ms |



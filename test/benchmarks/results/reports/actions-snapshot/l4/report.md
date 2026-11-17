# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `e3a4e937aa8852b24022c25f386fd5c3263ba9ab` e3a4e937aa8852b24022c25f386fd5c3263ba9ab; V10 baseline: none; harness and load generator from head.

Tier: L4; coverage: cold-mongo@1M, cold-pg@1M, sustainable-mongo@100k, sustainable-pg@100k, e5-mongo@100k, e5-pg@100k, cliff-e3-pg@100k, cliff-e3-mongo@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cold-mongo@1M | d1-head-e1-200 | steady | **p99 > 10× p50** | p50 3.6 ms, p99 154.6 ms |
| e5-mongo@100k | e1-handover | hand-over | **tail read the store** | gap=16 |
| e5-mongo@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +72.5% (±25.0%) |
| e5-mongo@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 4.4 ms, p99 66.0 ms |
| e5-mongo@100k | e3-handover | hand-over | **tail read the store** | gap=21 |
| e5-mongo@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +75.6% (±25.0%) |
| e5-mongo@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 9.2 ms, p99 5701.6 ms |
| e5-pg@100k | e1-handover | hand-over | **tail read the store** | gap=4 |
| e5-pg@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +36.3% (±25.0%) |
| e5-pg@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 4.8 ms, p99 1044.5 ms |
| e5-pg@100k | e3-steady | steady | **unstable** | second half against first, beyond the default limits: p50 -32.4% (±25.0%) |
| e5-pg@100k | e3-handover | hand-over | **tail read the store** | gap=13 |
| e5-pg@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +38.3% (±25.0%) |
| e5-pg@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 9.9 ms, p99 667.6 ms |

## Sustainable rates (capacity guidance)

### sustainable-mongo@100k

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **2975.1/s** | 18.2 ms | 198.7 ms | 0.95 | ≥ 5010.8/s | 2975.1–3131.7/s |
| E2 | **1272.2/s** | 28.8 ms | 190.5 ms | 2.20 | 2261.6/s | 1272.2–1342.8/s |
| E3 | **1008.3/s** | 31.4 ms | 173.1 ms | 2.80 | 1466.6/s | 1008.3–1054.1/s |

### sustainable-pg@100k

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **1957.8/s** | 11.1 ms | 139.3 ms | 1.33 | 4474.9/s | 1957.8–2097.6/s |
| E3 | **839.9/s** | 21.8 ms | 190.5 ms | 3.34 | 1493.1/s | 839.9–886.5/s |

## E5 cluster: steady and hand-over

### e5-mongo@100k

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 3.0 ms | 13.7 ms | 34.3 ms | 0 | 150/s | - | none |
| e1-handover | 150.0/s | 150.0/s | 0 | 4.4 ms | 66.0 ms | 1671.2 ms | 0 | 150/s | - | forwardFailed local=14 · stateRebuilt ungated-ran=76 · tailRead gap=16,start=26 |
| e3-steady | 50.0/s | 50.0/s | 0 | 7.1 ms | 26.9 ms | 59.8 ms | 0 | 50/s | - | none |
| e3-handover | 50.0/s | 49.6/s | 39 | 9.2 ms | 5701.6 ms | 9920.0 ms | 0 | 40/s | http-504-wow.wait.timeout 39 | stateRebuilt ungated-ran=70 · tailRead gap=21,start=25,idle=1 · waitTimedOut COMMITTED=39 · forwardFailed local=10 |

### e5-pg@100k

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 3.3 ms | 14.1 ms | 36.4 ms | 0 | 150/s | - | none |
| e1-handover | 150.0/s | 150.0/s | 0 | 4.8 ms | 1044.5 ms | 3129.3 ms | 0 | 150/s | - | forwardFailed local=15 · stateRebuilt ungated-ran=110 · tailRead gap=4,start=33 |
| e3-steady | 50.0/s | 50.0/s | 0 | 6.7 ms | 27.0 ms | 67.1 ms | 0 | 50/s | - | none |
| e3-handover | 50.0/s | 50.0/s | 1 | 9.9 ms | 667.6 ms | 2030.4 ms | 0 | 49/s | http-503-wow.node.closing 1 | forwardFailed local=12 · stateRebuilt ungated-ran=93 · tailRead gap=13,start=24,idle=5 · refused wow.node.closing=1 |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout | JDBC pool |
| --- | --- | --- | --- | --- | --- | --- |
| cold-mongo@1M | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 | - |
| cold-pg@1M | Intel(R) Xeon(R) 6975P-C | 4 | 15 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 | head 10 |
| sustainable-mongo@100k | Intel(R) Xeon(R) 6975P-C | 4 | 15 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 | - |
| sustainable-pg@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 | head 10 |
| e5-mongo@100k | Intel(R) Xeon(R) 6975P-C | 4 | 15 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 | - |
| e5-pg@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 | head 10 |
| cliff-e3-pg@100k | Intel(R) Xeon(R) 6975P-C | 4 | 15 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 | head 10 |
| cliff-e3-mongo@100k | Intel(R) Xeon(R) 6975P-C | 4 | 15 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 | - |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |

## E3 cliff steps

Fixed open-loop steps, a minute each on fresh containers. "Kept up": at most 1% failed and at least 97% of the rate completed. V10 signals count from the server start (seeding included): `tailRead` reasons other than `start` mean the tails fell back to the store, `refused wow.command.busy` that the node shed load.

### cliff-e3-pg@100k

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 2.7 ms | 7.6 ms | 31.5 ms | - | none |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 2.1 ms | 6.7 ms | 36.6 ms | - | none |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 1.8 ms | 6.4 ms | 52.2 ms | - | none |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 1.7 ms | 9.2 ms | 52.2 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 1.6 ms | 11.1 ms | 48.4 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 1.7 ms | 14.2 ms | 58.4 ms | - | none |

### cliff-e3-mongo@100k

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 5.0 ms | 14.8 ms | 36.1 ms | - | none |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 4.1 ms | 19.8 ms | 62.5 ms | - | none |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 4.0 ms | 34.0 ms | 122.9 ms | - | tailRead idle=1 |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 5.1 ms | 38.1 ms | 91.6 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 7.2 ms | 40.7 ms | 66.6 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 8.4 ms | 62.5 ms | 174.1 ms | - | none |

---

### cold-mongo@1M

A million accounts on MongoDB, uniform keys, so the caches miss (cold): E1 writes and E4 point reads at fixed rates.

scenarios below, uniform keys over 1000000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Cold: not primed on purpose; the caches start empty and the keys are uniform over 1000000 accounts.
The accounts exceed the node's state cache (a tenth of the 1 GB heap, about 104857 states of few requests): the steady state is a full cache with evictions.

- `head`: build e3a4e937aa

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| d1-head-e1-200 | head | 200.0/s | 200.0/s | 8000 | 0 | 3.6 ms | 72.7 ms | 154.6 ms | n/a | 234.5 ms | 0.37 cores | kafka 46% · mongo 77% | 0.13 of 1 | mongo 0.77 | 8.00 | 257.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| d2-head-e4-state-500 | head | 500.0/s | 500.0/s | 20000 | 0 | 0.7 ms | 2.7 ms | 4.9 ms | n/a | 30.5 ms | 0.20 cores | kafka 48% · mongo 41% | 0.14 of 1 | kafka 0.48 | 2.18 | 76.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cold-mongo@1M | d1-head-e1-200 | steady | **p99 > 10× p50** | p50 3.6 ms, p99 154.6 ms |


### cold-pg@1M

A million accounts on PostgreSQL, as cold-mongo.

scenarios below, uniform keys over 1000000 accounts; warm-up 30 s, measured 40 s. Runner: Intel(R) Xeon(R) 6975P-C, 4 cores.

Cold: not primed on purpose; the caches start empty and the keys are uniform over 1000000 accounts.
The accounts exceed the node's state cache (a tenth of the 1 GB heap, about 104857 states of few requests): the steady state is a full cache with evictions.

- `head`: build e3a4e937aa

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| d1-head-e1-200 | head | 200.0/s | 200.0/s | 8000 | 0 | 1.3 ms | 3.0 ms | 5.1 ms | n/a | 54.8 ms | 0.28 cores | kafka 42% · postgres 15% | 0.10 of 1 | kafka 0.42 | 4.26 | 120.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| d2-head-e4-state-500 | head | 500.0/s | 500.0/s | 20000 | 0 | 0.4 ms | 1.4 ms | 3.0 ms | n/a | 10.6 ms | 0.14 cores | kafka 37% · postgres 6% | 0.11 of 1 | kafka 0.37 | 1.15 | 35.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |


### sustainable-mongo@100k

Capacity guidance on MongoDB: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: Intel(R) Xeon(R) 6975P-C, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (24–26 s), then waited until the nodes were quiet again (4–5 s).

- `head`: build e3a4e937aa

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| head | **missing** | - | -/s | - |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-probe1 | head | 2505.4/s | 2505.4/s | 75162 | 0 | 11.6 ms | 47.6 ms | 86.5 ms | n/a | 151.6 ms | 0.94 cores | kafka 43% · mongo 142% | 0.56 of 1 | mongo 1.42 | 1.11 | 77.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-probe2 | head | 3758.1/s | 3742.5/s | 112276 | 467 | 94.2 ms | 434.2 ms | 626.7 ms | n/a | 835.6 ms | 1.01 cores | kafka 43% · mongo 147% | 0.70 of 1 | mongo 1.47 | 0.78 | 69.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | generator-overload 467 |
| e1-probe3 | head | 3131.7/s | 3131.7/s | 93951 | 0 | 37.4 ms | 165.9 ms | 230.4 ms | n/a | 319.5 ms | 0.97 cores | kafka 43% · mongo 148% | 0.64 of 1 | mongo 1.48 | 0.92 | 72.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| e1-probe4 | head | 2818.5/s | 2818.5/s | 84555 | 0 | 16.0 ms | 99.8 ms | 187.4 ms | n/a | 323.6 ms | 0.98 cores | kafka 42% · mongo 143% | 0.61 of 1 | mongo 1.43 | 1.00 | 74.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e1-probe5 | head | 2975.1/s | 2975.1/s | 89253 | 0 | 18.2 ms | 124.9 ms | 198.7 ms | n/a | 284.7 ms | 0.98 cores | kafka 45% · mongo 141% | 0.63 of 1 | mongo 1.41 | 0.95 | 74.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |
| e2-probe1 | head | 1130.8/s | 1130.8/s | 33924 | 0 | 22.1 ms | 82.4 ms | 122.4 ms | n/a | 172.0 ms | 0.97 cores | kafka 48% · mongo 133% | 0.46 of 1 | mongo 1.33 | 2.46 | 166.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-probe2 | head | 1696.2/s | 1696.2/s | 50885 | 0 | 84.5 ms | 354.3 ms | 475.1 ms | n/a | 671.7 ms | 1.02 cores | kafka 41% · mongo 141% | 0.62 of 1 | mongo 1.41 | 1.67 | 149.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| e2-probe3 | head | 1413.5/s | 1413.5/s | 42405 | 0 | 38.1 ms | 207.9 ms | 313.3 ms | n/a | 428.0 ms | 1.00 cores | kafka 42% · mongo 137% | 0.54 of 1 | mongo 1.37 | 1.98 | 156.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e2-probe4 | head | 1272.2/s | 1272.2/s | 38166 | 0 | 28.8 ms | 121.9 ms | 190.5 ms | n/a | 303.1 ms | 1.00 cores | kafka 48% · mongo 132% | 0.50 of 1 | mongo 1.32 | 2.20 | 161.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e2-probe5 | head | 1342.8/s | 1342.8/s | 40284 | 0 | 33.0 ms | 171.0 ms | 251.9 ms | n/a | 342.0 ms | 1.02 cores | kafka 42% · mongo 135% | 0.54 of 1 | mongo 1.35 | 2.07 | 157.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e3-probe1 | head | 733.3/s | 733.3/s | 21999 | 0 | 14.9 ms | 70.7 ms | 110.1 ms | n/a | 162.8 ms | 0.83 cores | kafka 45% · mongo 142% | 0.22 of 1 | mongo 1.42 | 3.68 | 226.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-probe2 | head | 1100.0/s | 1100.0/s | 33000 | 0 | 49.7 ms | 272.4 ms | 360.4 ms | n/a | 446.5 ms | 0.89 cores | kafka 43% · mongo 153% | 0.35 of 1 | mongo 1.53 | 2.60 | 194.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |
| e3-probe3 | head | 916.6/s | 916.6/s | 27498 | 0 | 21.8 ms | 109.1 ms | 179.2 ms | n/a | 258.0 ms | 0.85 cores | kafka 41% · mongo 151% | 0.32 of 1 | mongo 1.51 | 3.03 | 209.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e3-probe4 | head | 1008.3/s | 1008.3/s | 30249 | 0 | 31.4 ms | 133.1 ms | 173.1 ms | n/a | 213.0 ms | 0.90 cores | kafka 42% · mongo 151% | 0.33 of 1 | mongo 1.51 | 2.80 | 203.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-probe5 | head | 1054.1/s | 1054.1/s | 31622 | 0 | 31.5 ms | 147.5 ms | 215.0 ms | n/a | 290.8 ms | 0.88 cores | kafka 40% · mongo 154% | 0.34 of 1 | mongo 1.54 | 2.67 | 200.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **2975.1/s** | 18.2 ms | 198.7 ms | 0.95 | ≥ 5010.8/s | 2975.1–3131.7/s |
| E2 | **1272.2/s** | 28.8 ms | 190.5 ms | 2.20 | 2261.6/s | 1272.2–1342.8/s |
| E3 | **1008.3/s** | 31.4 ms | 173.1 ms | 2.80 | 1466.6/s | 1008.3–1054.1/s |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |


### sustainable-pg@100k

Capacity guidance on PostgreSQL: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (41–42 s), then waited until the nodes were quiet again (4–5 s).

- `head`: build e3a4e937aa

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| head | **missing** | - | -/s | - |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-probe1 | head | 2237.4/s | 2237.4/s | 67122 | 0 | 18.0 ms | 167.9 ms | 223.2 ms | n/a | 288.8 ms | 1.24 cores | kafka 60% · postgres 88% | 0.79 of 1 | server 1.24 | 1.21 | 73.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| e1-probe2 | head | 1118.7/s | 1118.7/s | 33561 | 0 | 3.4 ms | 10.5 ms | 20.1 ms | n/a | 62.0 ms | 1.07 cores | kafka 62% · postgres 77% | 0.47 of 1 | server 1.07 | 2.20 | 82.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-probe3 | head | 1678.1/s | 1678.1/s | 50343 | 0 | 7.6 ms | 45.8 ms | 127.0 ms | n/a | 209.9 ms | 1.12 cores | kafka 63% · postgres 88% | 0.66 of 1 | server 1.12 | 1.56 | 79.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-probe4 | head | 1957.8/s | 1957.8/s | 58734 | 0 | 11.1 ms | 75.3 ms | 139.3 ms | n/a | 222.2 ms | 1.17 cores | kafka 54% · postgres 90% | 0.70 of 1 | server 1.17 | 1.33 | 76.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-probe5 | head | 2097.6/s | 2097.6/s | 62928 | 0 | 15.5 ms | 167.9 ms | 372.7 ms | n/a | 520.2 ms | 1.19 cores | kafka 62% · postgres 86% | 0.77 of 1 | server 1.19 | 1.28 | 74.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-probe1 | head | 746.6/s | 746.6/s | 22398 | 0 | 15.7 ms | 50.9 ms | 92.7 ms | n/a | 137.2 ms | 1.14 cores | kafka 58% · postgres 103% | 0.33 of 1 | server 1.14 | 3.68 | 202.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-probe2 | head | 1119.8/s | 1075.1/s | 32253 | 1341 | 1474.6 ms | 2326.5 ms | 2736.1 ms | n/a | 3227.6 ms | 1.60 cores | kafka 53% · postgres 80% | 0.42 of 1 | server 1.60 | 2.73 | 180.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 20 s | generator-overload 1341 |
| e3-probe3 | head | 933.2/s | 933.2/s | 27996 | 0 | 175.1 ms | 958.5 ms | 1196.0 ms | n/a | 1458.2 ms | 1.50 cores | kafka 53% · postgres 87% | 0.41 of 1 | server 1.50 | 3.11 | 195.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 8 s | - |
| e3-probe4 | head | 839.9/s | 839.9/s | 25197 | 0 | 21.8 ms | 140.3 ms | 190.5 ms | n/a | 244.7 ms | 1.26 cores | kafka 56% · postgres 99% | 0.37 of 1 | server 1.26 | 3.34 | 194.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e3-probe5 | head | 886.5/s | 886.5/s | 26595 | 0 | 59.6 ms | 409.6 ms | 536.6 ms | n/a | 659.5 ms | 1.50 cores | kafka 54% · postgres 86% | 0.40 of 1 | server 1.50 | 3.28 | 188.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **1957.8/s** | 11.1 ms | 139.3 ms | 1.33 | 4474.9/s | 1957.8–2097.6/s |
| E3 | **839.9/s** | 21.8 ms | 190.5 ms | 3.34 | 1493.1/s | 839.9–886.5/s |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |


### e5-mongo@100k

E5 cluster of three nodes on MongoDB, requests round-robin: E1 and E3 steady, then with one node stopped and restarted mid-measurement (its partitions handed over and back).

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: Intel(R) Xeon(R) 6975P-C, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (85–93 s), then waited until the nodes were quiet again (4–4 s).

- `head`: build e3a4e937aa

Three nodes, requests round-robin, 90 s measured; in the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later.

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 3.0 ms | 13.7 ms | 34.3 ms | 0 | 150/s | - | none |
| e1-handover | 150.0/s | 150.0/s | 0 | 4.4 ms | 66.0 ms | 1671.2 ms | 0 | 150/s | - | forwardFailed local=14 · stateRebuilt ungated-ran=76 · tailRead gap=16,start=26 |
| e3-steady | 50.0/s | 50.0/s | 0 | 7.1 ms | 26.9 ms | 59.8 ms | 0 | 50/s | - | none |
| e3-handover | 50.0/s | 49.6/s | 39 | 9.2 ms | 5701.6 ms | 9920.0 ms | 0 | 40/s | http-504-wow.wait.timeout 39 | stateRebuilt ungated-ran=70 · tailRead gap=21,start=25,idle=1 · waitTimedOut COMMITTED=39 · forwardFailed local=10 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | head | 150.0/s | 150.0/s | 13500 | 0 | 3.0 ms | 8.0 ms | 13.7 ms | n/a | 34.3 ms | 0.46 cores | kafka 45% · mongo 48% | 0.11 of 1 | mongo 0.48 | 9.23 | 234.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-handover | head | 150.0/s | 150.0/s | 13500 | 0 | 4.4 ms | 18.2 ms | 66.0 ms | n/a | 1671.2 ms | - | kafka 44% · mongo 55% | 0.12 of 1 | - | - | - | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-steady | head | 50.0/s | 50.0/s | 4500 | 0 | 7.1 ms | 18.0 ms | 26.9 ms | n/a | 59.8 ms | 0.56 cores | kafka 44% · mongo 49% | 0.07 of 1 | server 0.56 | 29.75 | 737.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-handover | head | 50.0/s | 49.6/s | 4461 | 39 | 9.2 ms | 60.7 ms | 5701.6 ms | n/a | 9920.0 ms | - | kafka 44% · mongo 51% | 0.07 of 1 | - | - | - | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | http-504-wow.wait.timeout 39 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e5-mongo@100k | e1-handover | hand-over | **tail read the store** | gap=16 |
| e5-mongo@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +72.5% (±25.0%) |
| e5-mongo@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 4.4 ms, p99 66.0 ms |
| e5-mongo@100k | e3-handover | hand-over | **tail read the store** | gap=21 |
| e5-mongo@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +75.6% (±25.0%) |
| e5-mongo@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 9.2 ms, p99 5701.6 ms |


### e5-pg@100k

E5 cluster of three nodes on PostgreSQL, as e5-mongo.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (110–116 s), then waited until the nodes were quiet again (4–8 s).

- `head`: build e3a4e937aa

Three nodes, requests round-robin, 90 s measured; in the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later.

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 3.3 ms | 14.1 ms | 36.4 ms | 0 | 150/s | - | none |
| e1-handover | 150.0/s | 150.0/s | 0 | 4.8 ms | 1044.5 ms | 3129.3 ms | 0 | 150/s | - | forwardFailed local=15 · stateRebuilt ungated-ran=110 · tailRead gap=4,start=33 |
| e3-steady | 50.0/s | 50.0/s | 0 | 6.7 ms | 27.0 ms | 67.1 ms | 0 | 50/s | - | none |
| e3-handover | 50.0/s | 50.0/s | 1 | 9.9 ms | 667.6 ms | 2030.4 ms | 0 | 49/s | http-503-wow.node.closing 1 | forwardFailed local=12 · stateRebuilt ungated-ran=93 · tailRead gap=13,start=24,idle=5 · refused wow.node.closing=1 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | head | 150.0/s | 150.0/s | 13500 | 0 | 3.3 ms | 9.1 ms | 14.1 ms | n/a | 36.4 ms | 0.66 cores | kafka 58% · postgres 15% | 0.17 of 1 | server 0.66 | 9.30 | 161.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-handover | head | 150.0/s | 150.0/s | 13500 | 0 | 4.8 ms | 23.2 ms | 1044.5 ms | n/a | 3129.3 ms | - | kafka 57% · postgres 19% | 0.20 of 1 | - | - | - | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-steady | head | 50.0/s | 50.0/s | 4500 | 0 | 6.7 ms | 17.8 ms | 27.0 ms | n/a | 67.1 ms | 0.73 cores | kafka 57% · postgres 16% | 0.09 of 1 | server 0.73 | 29.08 | 514.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-handover | head | 50.0/s | 50.0/s | 4499 | 1 | 9.9 ms | 41.7 ms | 667.6 ms | n/a | 2030.4 ms | - | kafka 55% · postgres 18% | 0.10 of 1 | - | - | - | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | http-503-wow.node.closing 1 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e5-pg@100k | e1-handover | hand-over | **tail read the store** | gap=4 |
| e5-pg@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +36.3% (±25.0%) |
| e5-pg@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 4.8 ms, p99 1044.5 ms |
| e5-pg@100k | e3-steady | steady | **unstable** | second half against first, beyond the default limits: p50 -32.4% (±25.0%) |
| e5-pg@100k | e3-handover | hand-over | **tail read the store** | gap=13 |
| e5-pg@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +38.3% (±25.0%) |
| e5-pg@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 9.9 ms, p99 667.6 ms |


### cliff-e3-pg@100k

E3 on PostgreSQL in fixed open-loop steps: where completions stop following the rate, and whether the tails fell back to the store (a cliff) or the node refused work (overload).

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: Intel(R) Xeon(R) 6975P-C, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (20–24 s), then waited until the nodes were quiet again (3–5 s).

- `head`: build e3a4e937aa

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 2.7 ms | 7.6 ms | 31.5 ms | - | none |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 2.1 ms | 6.7 ms | 36.6 ms | - | none |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 1.8 ms | 6.4 ms | 52.2 ms | - | none |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 1.7 ms | 9.2 ms | 52.2 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 1.6 ms | 11.1 ms | 48.4 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 1.7 ms | 14.2 ms | 58.4 ms | - | none |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | head | 50.0/s | 50.0/s | 3000 | 0 | 2.7 ms | 5.5 ms | 7.6 ms | n/a | 31.5 ms | 0.21 cores | kafka 42% · postgres 10% | 0.07 of 1 | kafka 0.42 | 14.70 | 276.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s2-100 | head | 100.0/s | 100.0/s | 6000 | 0 | 2.1 ms | 4.7 ms | 6.7 ms | n/a | 36.6 ms | 0.32 cores | kafka 46% · postgres 17% | 0.09 of 1 | kafka 0.46 | 9.42 | 252.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s3-200 | head | 200.0/s | 200.0/s | 12000 | 0 | 1.8 ms | 4.0 ms | 6.4 ms | n/a | 52.2 ms | 0.52 cores | kafka 46% · postgres 28% | 0.12 of 1 | server 0.52 | 6.33 | 233.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s4-300 | head | 300.0/s | 300.0/s | 18000 | 0 | 1.7 ms | 4.6 ms | 9.2 ms | n/a | 52.2 ms | 0.75 cores | kafka 48% · postgres 42% | 0.15 of 1 | server 0.75 | 5.51 | 229.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s5-400 | head | 400.0/s | 400.0/s | 24000 | 0 | 1.6 ms | 5.3 ms | 11.1 ms | n/a | 48.4 ms | 0.90 cores | kafka 49% · postgres 52% | 0.17 of 1 | server 0.90 | 4.77 | 221.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s6-500 | head | 500.0/s | 500.0/s | 30000 | 0 | 1.7 ms | 7.4 ms | 14.2 ms | n/a | 58.4 ms | 1.06 cores | kafka 47% · postgres 62% | 0.20 of 1 | server 1.06 | 4.30 | 218.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |


### cliff-e3-mongo@100k

E3 on MongoDB in fixed open-loop steps, as cliff-e3-pg.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: Intel(R) Xeon(R) 6975P-C, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (24–25 s), then waited until the nodes were quiet again (3–7 s).

- `head`: build e3a4e937aa

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 5.0 ms | 14.8 ms | 36.1 ms | - | none |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 4.1 ms | 19.8 ms | 62.5 ms | - | none |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 4.0 ms | 34.0 ms | 122.9 ms | - | tailRead idle=1 |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 5.1 ms | 38.1 ms | 91.6 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 7.2 ms | 40.7 ms | 66.6 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 8.4 ms | 62.5 ms | 174.1 ms | - | none |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | head | 50.0/s | 50.0/s | 3000 | 0 | 5.0 ms | 10.0 ms | 14.8 ms | n/a | 36.1 ms | 0.22 cores | kafka 44% · mongo 45% | 0.07 of 1 | mongo 0.45 | 22.31 | 483.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s2-100 | head | 100.0/s | 100.0/s | 6000 | 0 | 4.1 ms | 9.8 ms | 19.8 ms | n/a | 62.5 ms | 0.32 cores | kafka 44% · mongo 63% | 0.10 of 1 | mongo 0.63 | 13.93 | 410.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s3-200 | head | 200.0/s | 200.0/s | 12000 | 0 | 4.0 ms | 17.5 ms | 34.0 ms | n/a | 122.9 ms | 0.55 cores | kafka 44% · mongo 95% | 0.13 of 1 | mongo 0.95 | 9.70 | 359.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s4-300 | head | 300.0/s | 300.0/s | 18000 | 0 | 5.1 ms | 22.7 ms | 38.1 ms | n/a | 91.6 ms | 0.68 cores | kafka 47% · mongo 118% | 0.16 of 1 | mongo 1.18 | 7.77 | 328.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s5-400 | head | 400.0/s | 400.0/s | 24000 | 0 | 7.2 ms | 26.4 ms | 40.7 ms | n/a | 66.6 ms | 0.75 cores | kafka 46% · mongo 129% | 0.18 of 1 | mongo 1.29 | 6.26 | 300.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s6-500 | head | 500.0/s | 500.0/s | 30000 | 0 | 8.4 ms | 36.9 ms | 62.5 ms | n/a | 174.1 ms | 0.79 cores | kafka 43% · mongo 127% | 0.22 of 1 | mongo 1.27 | 4.97 | 270.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |



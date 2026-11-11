# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `main` d8284109df87504739da5b86dbc1bb3478ae6e04; V10 baseline: none; harness and load generator from head.

Tier: L4; coverage: cold-mongo@1M, cold-pg@1M, sustainable-mongo@100k, sustainable-pg@100k, e5-mongo@100k, e5-pg@100k, cliff-e3-pg@100k, cliff-e3-mongo@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e5-mongo@100k | e1-steady | steady | **unstable** | second half against first, beyond the default limits: p50 -39.7% (±25.0%) |
| e5-mongo@100k | e1-handover | hand-over | **tail read the store** | gap=12 |
| e5-mongo@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 11.5 ms, p99 2359.3 ms |
| e5-mongo@100k | e3-steady | steady | **unstable** | second half against first, beyond the default limits: p50 -50.2% (±25.0%) |
| e5-mongo@100k | e3-handover | hand-over | **tail read the store** | gap=22 |
| e5-mongo@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 22.4 ms, p99 6389.8 ms |
| e5-pg@100k | e1-handover | hand-over | **tail read the store** | gap=5 |
| e5-pg@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +90.0% (±25.0%) |
| e5-pg@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 4.8 ms, p99 487.4 ms |
| e5-pg@100k | e3-handover | hand-over | **tail read the store** | gap=5 |
| e5-pg@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +53.6% (±25.0%) |
| e5-pg@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 10.6 ms, p99 1065.0 ms |
| cliff-e3-pg@100k | s3-200 | steady | **p99 > 10× p50** | p50 2.0 ms, p99 56.6 ms |
| cliff-e3-pg@100k | s4-300 | steady | **p99 > 10× p50** | p50 2.0 ms, p99 74.2 ms |
| cliff-e3-pg@100k | s5-400 | steady | **p99 > 10× p50** | p50 2.2 ms, p99 103.9 ms |
| cliff-e3-pg@100k | s6-500 | steady | **unstable** | second half against first, beyond the default limits: p50 -32.4% (±25.0%) |
| cliff-e3-pg@100k | s6-500 | steady | **p99 > 10× p50** | p50 3.2 ms, p99 137.2 ms |
| cliff-e3-mongo@100k | s1-50 | steady | **p99 > 10× p50** | p50 4.7 ms, p99 64.0 ms |
| cliff-e3-mongo@100k | s2-100 | steady | **p99 > 10× p50** | p50 4.5 ms, p99 249.9 ms |
| cliff-e3-mongo@100k | s3-200 | steady | **p99 > 10× p50** | p50 3.6 ms, p99 90.1 ms |
| cliff-e3-mongo@100k | s4-300 | steady | **p99 > 10× p50** | p50 4.3 ms, p99 97.8 ms |
| cliff-e3-mongo@100k | s5-400 | steady | **p99 > 10× p50** | p50 7.1 ms, p99 182.3 ms |
| cliff-e3-mongo@100k | s6-500 | steady | **p99 > 10× p50** | p50 9.2 ms, p99 148.5 ms |

## Sustainable rates (capacity guidance)

### sustainable-mongo@100k

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **1353.8/s** | 19.1 ms | 171.0 ms | 2.07 | 2166.1/s | 1353.8–1421.5/s |
| E2 | **676.7/s** | 29.2 ms | 196.6 ms | 4.02 | 1353.4/s | 676.7–719.0/s |
| E3 | **485.5/s** | 26.6 ms | 161.8 ms | 5.75 | 776.8/s | 485.5–509.7/s |

### sustainable-pg@100k

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **2609.8/s** | 4.6 ms | 160.8 ms | 0.97 | 4912.5/s | 2609.8–2763.3/s |
| E3 | **741.9/s** | 3.0 ms | 188.4 ms | 3.23 | 2374.0/s | 741.9–816.1/s |

## E5 cluster: steady and hand-over

### e5-mongo@100k

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 4.7 ms | 31.5 ms | 81.4 ms | 0 | 150/s | - | none |
| e1-handover | 150.0/s | 150.0/s | 1 | 11.5 ms | 2359.3 ms | 5079.0 ms | 0 | 149/s | exception-IOException 1 | forwardFailed local=30 · tailRead gap=12,start=26,idle=4 |
| e3-steady | 50.0/s | 50.0/s | 0 | 10.5 ms | 49.7 ms | 107.5 ms | 0 | 50/s | - | none |
| e3-handover | 50.0/s | 49.7/s | 30 | 22.4 ms | 6389.8 ms | 9712.7 ms | 0 | 44/s | http-504-wow.wait.timeout 29 · http-503-wow.node.closing 1 | forwardFailed local=27 · tailRead gap=22,start=24,idle=8 · waitTimedOut COMMITTED=29 · refused wow.node.closing=1 |

### e5-pg@100k

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 3.1 ms | 13.9 ms | 54.5 ms | 0 | 150/s | - | none |
| e1-handover | 150.0/s | 150.0/s | 0 | 4.8 ms | 487.4 ms | 1785.9 ms | 0 | 150/s | - | forwardFailed local=23 · tailRead gap=5,idle=2,start=36 |
| e3-steady | 50.0/s | 50.0/s | 0 | 6.7 ms | 23.4 ms | 50.9 ms | 0 | 50/s | - | none |
| e3-handover | 50.0/s | 50.0/s | 0 | 10.6 ms | 1065.0 ms | 4170.1 ms | 0 | 50/s | - | forwardFailed local=12 · tailRead gap=5,start=22,idle=1 |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| cold-mongo@1M | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |
| cold-pg@1M | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |
| sustainable-mongo@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |
| sustainable-pg@100k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |
| e5-mongo@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |
| e5-pg@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |
| cliff-e3-pg@100k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |
| cliff-e3-mongo@100k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |

## E3 cliff steps

Fixed open-loop steps, a minute each on fresh containers. "Kept up": at most 1% failed and at least 97% of the rate completed. V10 signals count from the server start (seeding included): `tailRead` reasons other than `start` mean the tails fell back to the store, `refused wow.command.busy` that the node shed load.

### cliff-e3-pg@100k

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 2.8 ms | 13.3 ms | 212.2 ms | - | none |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 2.3 ms | 21.1 ms | 236.5 ms | - | none |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 2.0 ms | 56.6 ms | 261.1 ms | - | none |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 2.0 ms | 74.2 ms | 323.6 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 2.2 ms | 103.9 ms | 333.8 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 3.2 ms | 137.2 ms | 325.6 ms | - | none |

### cliff-e3-mongo@100k

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 4.7 ms | 64.0 ms | 352.2 ms | - | none |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 4.5 ms | 249.9 ms | 454.7 ms | - | none |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 3.6 ms | 90.1 ms | 303.1 ms | - | none |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 4.3 ms | 97.8 ms | 220.2 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 7.1 ms | 182.3 ms | 358.4 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 9.2 ms | 148.5 ms | 411.6 ms | - | none |

---

### cold-mongo@1M

A million accounts on MongoDB, uniform keys, so the caches miss (cold): E1 writes and E4 point reads at fixed rates.

scenarios below, uniform keys over 1000000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Cold: not primed on purpose; the caches start empty and the keys are uniform over 1000000 accounts.
The accounts exceed the node's state cache (a tenth of the 1 GB heap, about 104857 states of few requests): the steady state is a full cache with evictions.

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| d1-head-e1-200 | head | 200.0/s | 200.0/s | 8000 | 0 | 4.3 ms | 20.0 ms | 43.3 ms | n/a | 101.9 ms | 0.45 cores | kafka 56% · mongo 97% | 0.18 of 1 | mongo 0.97 | 9.89 | 290.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| d2-head-e4-state-500 | head | 500.0/s | 500.0/s | 20000 | 0 | 0.9 ms | 3.5 ms | 6.7 ms | n/a | 45.3 ms | 0.25 cores | kafka 56% · mongo 48% | 0.18 of 1 | kafka 0.56 | 2.57 | 76.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |


### cold-pg@1M

A million accounts on PostgreSQL, as cold-mongo.

scenarios below, uniform keys over 1000000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Cold: not primed on purpose; the caches start empty and the keys are uniform over 1000000 accounts.
The accounts exceed the node's state cache (a tenth of the 1 GB heap, about 104857 states of few requests): the steady state is a full cache with evictions.

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| d1-head-e1-200 | head | 200.0/s | 200.0/s | 8000 | 0 | 1.9 ms | 4.5 ms | 6.8 ms | n/a | 19.1 ms | 0.36 cores | kafka 60% · postgres 25% | 0.14 of 1 | kafka 0.60 | 6.03 | 136.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| d2-head-e4-state-500 | head | 500.0/s | 500.0/s | 20000 | 0 | 0.6 ms | 2.2 ms | 4.0 ms | n/a | 9.2 ms | 0.20 cores | kafka 55% · postgres 10% | 0.16 of 1 | kafka 0.55 | 1.69 | 36.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |


### sustainable-mongo@100k

Capacity guidance on MongoDB: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (51–56 s), then waited until the nodes were quiet again (4–6 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| head | **missing** | - | -/s | - |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-probe1 | head | 1083.1/s | 1083.1/s | 32492 | 0 | 11.6 ms | 64.3 ms | 108.0 ms | n/a | 173.1 ms | 0.82 cores | kafka 53% · mongo 136% | 0.43 of 1 | mongo 1.36 | 2.51 | 117.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-probe2 | head | 1624.6/s | 1624.6/s | 48738 | 0 | 48.1 ms | 411.6 ms | 663.6 ms | n/a | 876.5 ms | 0.94 cores | kafka 53% · mongo 136% | 0.59 of 1 | mongo 1.36 | 1.74 | 101.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| e1-probe3 | head | 1353.8/s | 1353.8/s | 40614 | 0 | 19.1 ms | 96.8 ms | 171.0 ms | n/a | 329.7 ms | 0.91 cores | kafka 48% · mongo 141% | 0.54 of 1 | mongo 1.41 | 2.07 | 109.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-probe4 | head | 1489.2/s | 1489.2/s | 44675 | 0 | 22.4 ms | 172.0 ms | 247.8 ms | n/a | 350.2 ms | 0.91 cores | kafka 47% · mongo 143% | 0.57 of 1 | mongo 1.43 | 1.88 | 106.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e1-probe5 | head | 1421.5/s | 1421.5/s | 42645 | 0 | 24.3 ms | 143.4 ms | 219.1 ms | n/a | 299.0 ms | 0.89 cores | kafka 49% · mongo 140% | 0.53 of 1 | mongo 1.40 | 1.96 | 107.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |
| e2-probe1 | head | 676.7/s | 676.7/s | 20300 | 0 | 29.2 ms | 130.6 ms | 196.6 ms | n/a | 252.9 ms | 0.89 cores | kafka 56% · mongo 127% | 0.49 of 1 | mongo 1.27 | 4.02 | 204.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-probe2 | head | 1015.0/s | 1015.0/s | 30450 | 0 | 146.4 ms | 602.1 ms | 823.3 ms | n/a | 1343.5 ms | 1.01 cores | kafka 47% · mongo 132% | 0.65 of 1 | mongo 1.32 | 2.76 | 181.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| e2-probe3 | head | 845.9/s | 845.9/s | 25377 | 0 | 56.8 ms | 213.0 ms | 288.8 ms | n/a | 362.5 ms | 0.98 cores | kafka 49% · mongo 129% | 0.57 of 1 | mongo 1.29 | 3.27 | 188.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-probe4 | head | 761.3/s | 761.3/s | 22839 | 0 | 29.1 ms | 159.7 ms | 219.1 ms | n/a | 329.7 ms | 0.96 cores | kafka 46% · mongo 132% | 0.53 of 1 | mongo 1.32 | 3.60 | 199.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-probe5 | head | 719.0/s | 719.0/s | 21570 | 0 | 30.5 ms | 190.5 ms | 403.5 ms | n/a | 569.3 ms | 0.91 cores | kafka 50% · mongo 129% | 0.50 of 1 | mongo 1.29 | 3.76 | 200.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e3-probe1 | head | 388.4/s | 388.4/s | 11652 | 0 | 18.2 ms | 82.9 ms | 142.3 ms | n/a | 232.4 ms | 0.75 cores | kafka 55% · mongo 142% | 0.24 of 1 | mongo 1.42 | 6.99 | 331.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-probe2 | head | 582.6/s | 582.6/s | 17478 | 0 | 63.0 ms | 340.0 ms | 462.8 ms | n/a | 692.2 ms | 0.84 cores | kafka 53% · mongo 148% | 0.25 of 1 | mongo 1.48 | 4.89 | 280.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e3-probe3 | head | 485.5/s | 485.5/s | 14565 | 0 | 26.6 ms | 108.5 ms | 161.8 ms | n/a | 227.3 ms | 0.79 cores | kafka 54% · mongo 146% | 0.23 of 1 | mongo 1.46 | 5.75 | 301.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-probe4 | head | 534.0/s | 534.0/s | 16020 | 0 | 32.1 ms | 200.7 ms | 434.2 ms | n/a | 663.6 ms | 0.83 cores | kafka 50% · mongo 149% | 0.25 of 1 | mongo 1.49 | 5.28 | 290.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-probe5 | head | 509.7/s | 509.7/s | 15291 | 0 | 35.1 ms | 153.6 ms | 215.0 ms | n/a | 286.7 ms | 0.81 cores | kafka 52% · mongo 147% | 0.22 of 1 | mongo 1.47 | 5.50 | 297.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **1353.8/s** | 19.1 ms | 171.0 ms | 2.07 | 2166.1/s | 1353.8–1421.5/s |
| E2 | **676.7/s** | 29.2 ms | 196.6 ms | 4.02 | 1353.4/s | 676.7–719.0/s |
| E3 | **485.5/s** | 26.6 ms | 161.8 ms | 5.75 | 776.8/s | 485.5–509.7/s |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |


### sustainable-pg@100k

Capacity guidance on PostgreSQL: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (23–30 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| head | **missing** | - | -/s | - |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-probe1 | head | 2456.3/s | 2456.3/s | 73689 | 0 | 5.2 ms | 74.2 ms | 186.4 ms | n/a | 292.9 ms | 1.13 cores | kafka 52% · postgres 96% | 0.59 of 1 | server 1.13 | 1.06 | 96.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-probe2 | head | 3684.4/s | 3684.4/s | 110531 | 0 | 66.0 ms | 272.4 ms | 356.4 ms | n/a | 446.5 ms | 1.18 cores | kafka 51% · postgres 92% | 0.76 of 1 | server 1.18 | 0.71 | 88.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | - |
| e1-probe3 | head | 3070.3/s | 3070.3/s | 92109 | 0 | 12.5 ms | 128.0 ms | 344.1 ms | n/a | 516.1 ms | 1.14 cores | kafka 51% · postgres 89% | 0.66 of 1 | server 1.14 | 0.83 | 92.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e1-probe4 | head | 2763.3/s | 2763.3/s | 82898 | 0 | 11.6 ms | 89.1 ms | 224.3 ms | n/a | 438.3 ms | 1.05 cores | kafka 52% · postgres 92% | 0.62 of 1 | server 1.05 | 0.90 | 92.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e1-probe5 | head | 2609.8/s | 2609.8/s | 78293 | 0 | 4.6 ms | 48.1 ms | 160.8 ms | n/a | 325.6 ms | 1.11 cores | kafka 48% · postgres 94% | 0.59 of 1 | server 1.11 | 0.97 | 97.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-probe1 | head | 1187.0/s | 1187.0/s | 35610 | 0 | 39.7 ms | 193.5 ms | 483.3 ms | n/a | 630.8 ms | 1.10 cores | kafka 51% · postgres 105% | 0.36 of 1 | server 1.10 | 2.24 | 236.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e3-probe2 | head | 593.5/s | 593.5/s | 17805 | 0 | 4.0 ms | 66.6 ms | 152.6 ms | n/a | 290.8 ms | 0.89 cores | kafka 47% · postgres 75% | 0.16 of 1 | server 0.89 | 3.57 | 258.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-probe3 | head | 890.3/s | 890.3/s | 26709 | 0 | 4.1 ms | 86.0 ms | 319.5 ms | n/a | 503.8 ms | 1.07 cores | kafka 44% · postgres 97% | 0.23 of 1 | server 1.07 | 2.79 | 259.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-probe4 | head | 741.9/s | 741.9/s | 22257 | 0 | 3.0 ms | 41.2 ms | 188.4 ms | n/a | 380.9 ms | 1.04 cores | kafka 45% · postgres 90% | 0.20 of 1 | server 1.04 | 3.23 | 261.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-probe5 | head | 816.1/s | 816.1/s | 24482 | 0 | 5.3 ms | 69.6 ms | 226.3 ms | n/a | 444.4 ms | 1.03 cores | kafka 46% · postgres 94% | 0.23 of 1 | server 1.03 | 2.98 | 257.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |

| Scenario | Sustainable rate (≤1% errors, p99 ≤ 200 ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | **2609.8/s** | 4.6 ms | 160.8 ms | 0.97 | 4912.5/s | 2609.8–2763.3/s |
| E3 | **741.9/s** | 3.0 ms | 188.4 ms | 3.23 | 2374.0/s | 741.9–816.1/s |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |


### e5-mongo@100k

E5 cluster of three nodes on MongoDB, requests round-robin: E1 and E3 steady, then with one node stopped and restarted mid-measurement (its partitions handed over and back).

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (152–160 s), then waited until the nodes were quiet again (4–8 s).

Three nodes, requests round-robin, 90 s measured; in the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later.

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 4.7 ms | 31.5 ms | 81.4 ms | 0 | 150/s | - | none |
| e1-handover | 150.0/s | 150.0/s | 1 | 11.5 ms | 2359.3 ms | 5079.0 ms | 0 | 149/s | exception-IOException 1 | forwardFailed local=30 · tailRead gap=12,start=26,idle=4 |
| e3-steady | 50.0/s | 50.0/s | 0 | 10.5 ms | 49.7 ms | 107.5 ms | 0 | 50/s | - | none |
| e3-handover | 50.0/s | 49.7/s | 30 | 22.4 ms | 6389.8 ms | 9712.7 ms | 0 | 44/s | http-504-wow.wait.timeout 29 · http-503-wow.node.closing 1 | forwardFailed local=27 · tailRead gap=22,start=24,idle=8 · waitTimedOut COMMITTED=29 · refused wow.node.closing=1 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | head | 150.0/s | 150.0/s | 13500 | 0 | 4.7 ms | 18.2 ms | 31.5 ms | n/a | 81.4 ms | 0.80 cores | kafka 53% · mongo 65% | 0.15 of 1 | server 0.80 | 13.15 | 257.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-handover | head | 150.0/s | 150.0/s | 13499 | 1 | 11.5 ms | 132.1 ms | 2359.3 ms | n/a | 5079.0 ms | - | kafka 52% · mongo 73% | 0.18 of 1 | - | - | - | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | exception-IOException 1 |
| e3-steady | head | 50.0/s | 50.0/s | 4500 | 0 | 10.5 ms | 33.5 ms | 49.7 ms | n/a | 107.5 ms | 0.87 cores | kafka 52% · mongo 63% | 0.10 of 1 | server 0.87 | 40.30 | 759.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-handover | head | 50.0/s | 49.7/s | 4470 | 30 | 22.4 ms | 1286.1 ms | 6389.8 ms | n/a | 9712.7 ms | - | kafka 50% · mongo 69% | 0.11 of 1 | - | - | - | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | http-504-wow.wait.timeout 29 · http-503-wow.node.closing 1 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e5-mongo@100k | e1-steady | steady | **unstable** | second half against first, beyond the default limits: p50 -39.7% (±25.0%) |
| e5-mongo@100k | e1-handover | hand-over | **tail read the store** | gap=12 |
| e5-mongo@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 11.5 ms, p99 2359.3 ms |
| e5-mongo@100k | e3-steady | steady | **unstable** | second half against first, beyond the default limits: p50 -50.2% (±25.0%) |
| e5-mongo@100k | e3-handover | hand-over | **tail read the store** | gap=22 |
| e5-mongo@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 22.4 ms, p99 6389.8 ms |


### e5-pg@100k

E5 cluster of three nodes on PostgreSQL, as e5-mongo.

scenarios below, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (118–131 s), then waited until the nodes were quiet again (4–7 s).

Three nodes, requests round-robin, 90 s measured; in the hand-over runs node 2 is stopped 25 s into the measured phase and restarted 20 s later.

| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | 150.0/s | 150.0/s | 0 | 3.1 ms | 13.9 ms | 54.5 ms | 0 | 150/s | - | none |
| e1-handover | 150.0/s | 150.0/s | 0 | 4.8 ms | 487.4 ms | 1785.9 ms | 0 | 150/s | - | forwardFailed local=23 · tailRead gap=5,idle=2,start=36 |
| e3-steady | 50.0/s | 50.0/s | 0 | 6.7 ms | 23.4 ms | 50.9 ms | 0 | 50/s | - | none |
| e3-handover | 50.0/s | 50.0/s | 0 | 10.6 ms | 1065.0 ms | 4170.1 ms | 0 | 50/s | - | forwardFailed local=12 · tailRead gap=5,start=22,idle=1 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-steady | head | 150.0/s | 150.0/s | 13500 | 0 | 3.1 ms | 8.6 ms | 13.9 ms | n/a | 54.5 ms | 0.64 cores | kafka 57% · postgres 16% | 0.16 of 1 | server 0.64 | 9.20 | 179.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-handover | head | 150.0/s | 150.0/s | 13500 | 0 | 4.8 ms | 29.3 ms | 487.4 ms | n/a | 1785.9 ms | - | kafka 58% · postgres 21% | 0.18 of 1 | - | - | - | - | datasets 8 of 8 · default 2–3 of 5, lowered 0, pressed 1 s | - |
| e3-steady | head | 50.0/s | 50.0/s | 4500 | 0 | 6.7 ms | 16.1 ms | 23.4 ms | n/a | 50.9 ms | 0.71 cores | kafka 57% · postgres 16% | 0.10 of 1 | server 0.71 | 28.73 | 561.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-handover | head | 50.0/s | 50.0/s | 4500 | 0 | 10.6 ms | 45.1 ms | 1065.0 ms | n/a | 4170.1 ms | - | kafka 56% · postgres 20% | 0.10 of 1 | - | - | - | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e5-pg@100k | e1-handover | hand-over | **tail read the store** | gap=5 |
| e5-pg@100k | e1-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +90.0% (±25.0%) |
| e5-pg@100k | e1-handover | hand-over | **p99 > 10× p50** | p50 4.8 ms, p99 487.4 ms |
| e5-pg@100k | e3-handover | hand-over | **tail read the store** | gap=5 |
| e5-pg@100k | e3-handover | hand-over | **unstable** | second half against first, beyond the default limits: p50 +53.6% (±25.0%) |
| e5-pg@100k | e3-handover | hand-over | **p99 > 10× p50** | p50 10.6 ms, p99 1065.0 ms |


### cliff-e3-pg@100k

E3 on PostgreSQL in fixed open-loop steps: where completions stop following the rate, and whether the tails fell back to the store (a cliff) or the node refused work (overload).

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (31–32 s), then waited until the nodes were quiet again (3–4 s).

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 2.8 ms | 13.3 ms | 212.2 ms | - | none |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 2.3 ms | 21.1 ms | 236.5 ms | - | none |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 2.0 ms | 56.6 ms | 261.1 ms | - | none |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 2.0 ms | 74.2 ms | 323.6 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 2.2 ms | 103.9 ms | 333.8 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 3.2 ms | 137.2 ms | 325.6 ms | - | none |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | head | 50.0/s | 50.0/s | 3000 | 0 | 2.8 ms | 6.6 ms | 13.3 ms | n/a | 212.2 ms | 0.19 cores | kafka 45% · postgres 12% | 0.07 of 1 | kafka 0.45 | 15.34 | 317.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s2-100 | head | 100.0/s | 100.0/s | 6000 | 0 | 2.3 ms | 6.1 ms | 21.1 ms | n/a | 236.5 ms | 0.33 cores | kafka 48% · postgres 20% | 0.11 of 1 | kafka 0.48 | 10.11 | 294.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s3-200 | head | 200.0/s | 200.0/s | 12000 | 0 | 2.0 ms | 6.1 ms | 56.6 ms | n/a | 261.1 ms | 0.53 cores | kafka 52% · postgres 35% | 0.15 of 1 | server 0.53 | 7.01 | 278.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s4-300 | head | 300.0/s | 300.0/s | 18000 | 0 | 2.0 ms | 9.7 ms | 74.2 ms | n/a | 323.6 ms | 0.73 cores | kafka 53% · postgres 49% | 0.18 of 1 | server 0.73 | 5.85 | 269.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s5-400 | head | 400.0/s | 400.0/s | 24000 | 0 | 2.2 ms | 18.4 ms | 103.9 ms | n/a | 333.8 ms | 0.90 cores | kafka 54% · postgres 63% | 0.21 of 1 | server 0.90 | 5.17 | 264.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| s6-500 | head | 500.0/s | 500.0/s | 30000 | 0 | 3.2 ms | 25.5 ms | 137.2 ms | n/a | 325.6 ms | 0.99 cores | kafka 53% · postgres 77% | 0.22 of 1 | server 0.99 | 4.59 | 267.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cliff-e3-pg@100k | s3-200 | steady | **p99 > 10× p50** | p50 2.0 ms, p99 56.6 ms |
| cliff-e3-pg@100k | s4-300 | steady | **p99 > 10× p50** | p50 2.0 ms, p99 74.2 ms |
| cliff-e3-pg@100k | s5-400 | steady | **p99 > 10× p50** | p50 2.2 ms, p99 103.9 ms |
| cliff-e3-pg@100k | s6-500 | steady | **unstable** | second half against first, beyond the default limits: p50 -32.4% (±25.0%) |
| cliff-e3-pg@100k | s6-500 | steady | **p99 > 10× p50** | p50 3.2 ms, p99 137.2 ms |


### cliff-e3-mongo@100k

E3 on MongoDB in fixed open-loop steps, as cliff-e3-pg.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (27–30 s), then waited until the nodes were quiet again (3–4 s).

| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | 50.0/s | 50.0/s | yes | 0 | 4.7 ms | 64.0 ms | 352.2 ms | - | none |
| s2-100 | 100.0/s | 100.0/s | yes | 0 | 4.5 ms | 249.9 ms | 454.7 ms | - | none |
| s3-200 | 200.0/s | 200.0/s | yes | 0 | 3.6 ms | 90.1 ms | 303.1 ms | - | none |
| s4-300 | 300.0/s | 300.0/s | yes | 0 | 4.3 ms | 97.8 ms | 220.2 ms | - | none |
| s5-400 | 400.0/s | 400.0/s | yes | 0 | 7.1 ms | 182.3 ms | 358.4 ms | - | none |
| s6-500 | 500.0/s | 500.0/s | yes | 0 | 9.2 ms | 148.5 ms | 411.6 ms | - | none |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| s1-50 | head | 50.0/s | 50.0/s | 3000 | 0 | 4.7 ms | 12.0 ms | 64.0 ms | n/a | 352.2 ms | 0.20 cores | kafka 42% · mongo 41% | 0.07 of 1 | kafka 0.42 | 20.58 | 542.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s2-100 | head | 100.0/s | 100.0/s | 6000 | 0 | 4.5 ms | 16.6 ms | 249.9 ms | n/a | 454.7 ms | 0.32 cores | kafka 43% · mongo 57% | 0.12 of 1 | mongo 0.57 | 13.21 | 469.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s3-200 | head | 200.0/s | 200.0/s | 12000 | 0 | 3.6 ms | 24.4 ms | 90.1 ms | n/a | 303.1 ms | 0.46 cores | kafka 45% · mongo 84% | 0.14 of 1 | mongo 0.84 | 8.72 | 425.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s4-300 | head | 300.0/s | 300.0/s | 18000 | 0 | 4.3 ms | 29.7 ms | 97.8 ms | n/a | 220.2 ms | 0.59 cores | kafka 43% · mongo 100% | 0.16 of 1 | mongo 1.00 | 6.74 | 399.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s5-400 | head | 400.0/s | 400.0/s | 24000 | 0 | 7.1 ms | 46.8 ms | 182.3 ms | n/a | 358.4 ms | 0.66 cores | kafka 44% · mongo 117% | 0.18 of 1 | mongo 1.17 | 5.66 | 369.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| s6-500 | head | 500.0/s | 500.0/s | 30000 | 0 | 9.2 ms | 57.3 ms | 148.5 ms | n/a | 411.6 ms | 0.72 cores | kafka 45% · mongo 132% | 0.21 of 1 | mongo 1.32 | 4.97 | 345.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| cliff-e3-mongo@100k | s1-50 | steady | **p99 > 10× p50** | p50 4.7 ms, p99 64.0 ms |
| cliff-e3-mongo@100k | s2-100 | steady | **p99 > 10× p50** | p50 4.5 ms, p99 249.9 ms |
| cliff-e3-mongo@100k | s3-200 | steady | **p99 > 10× p50** | p50 3.6 ms, p99 90.1 ms |
| cliff-e3-mongo@100k | s4-300 | steady | **p99 > 10× p50** | p50 4.3 ms, p99 97.8 ms |
| cliff-e3-mongo@100k | s5-400 | steady | **p99 > 10× p50** | p50 7.1 ms, p99 182.3 ms |
| cliff-e3-mongo@100k | s6-500 | steady | **p99 > 10× p50** | p50 9.2 ms, p99 148.5 ms |



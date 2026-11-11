# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `a7c372ad3ef808f479776a6183247c39f46189fc` a7c372ad3ef808f479776a6183247c39f46189fc; V10 baseline: `b435768e49` b435768e4979f146e5c52142410f87a06b941861; harness and load generator from head.

Tier: custom; coverage: e3-mongo@100k, e3-mongo@1k, e3-pg@100k, e3-pg@1k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@100k | head against base | cost | **more allocation** | head allocates ×1.07 the bytes per operation of base (median of the equal-rate pairs) |
| e3-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +19.7% (±18.4%) |
| e3-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +47.0% (±18.4%) |
| e3-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +26.7% (±18.4%) |
| e3-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +53.0% (±18.4%) |
| e3-mongo@100k | e1-base | steady | **p99 > 10× p50** | p50 62.2 ms, p99 667.6 ms |
| e3-mongo@100k | e1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -24.1% (±18.4%) |
| e3-mongo@100k | e2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -21.6% (±18.4%) |
| e3-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +105.3% (±18.4%) |
| e3-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -61.9% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -60.1% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 52.0 ms, p99 577.5 ms |
| e3-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -60.1% (±25.0%) |
| e3-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -28.1% (±25.0%) |
| e3-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -71.6% (±25.0%) |
| e3-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 67.6 ms, p99 3227.6 ms |
| e3-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.4% (±25.0%) |
| e3-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -25.4% (±25.0%) |
| e3-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -53.8% (±25.0%) |
| e3-pg@100k | head against base | cost | **more allocation** | head allocates ×1.07 the bytes per operation of base (median of the equal-rate pairs) |
| e3-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +44.7% (±37.3%) |
| e3-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 64.3 ms, p99 868.4 ms |
| e3-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +131.2% (±37.3%) |
| e3-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 93.7 ms, p99 950.3 ms |
| e3-pg@1k | head against base | cost | **more allocation** | head allocates ×1.12 the bytes per operation of base (median of the equal-rate pairs) |
| e3-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -78.4% (±25.0%) |
| e3-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 69.6 ms, p99 954.4 ms |
| e3-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -80.5% (±25.0%) |
| e3-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 42.8 ms, p99 1237.0 ms |
| e3-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -92.1% (±25.0%) |
| e3-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 177.2 ms, p99 4390.9 ms |
| e3-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -78.3% (±25.0%) |
| e3-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -72.2% (±25.0%) |
| e3-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -77.4% (±25.0%) |
| e3-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 155.6 ms, p99 1851.4 ms |
| e3-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -88.0% (±25.0%) |
| e3-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -82.2% (±25.0%) |
| e3-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 127.5 ms, p99 2179.1 ms |
| e3-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -87.2% (±25.0%) |
| e3-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -70.7% (±25.0%) |

## Significance

Beyond noise: 2 of 12 ratios; 2 of 4 suites have an A/A noise threshold (test/benchmarks/noise.json). A ratio counts when its 95% bootstrap interval (over the measured seconds) clears the suite's threshold; without one it is `uncalibrated`.

| Suite | b/a | Metric | Rounds | Ratio [95% CI] | Noise | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| e3-mongo@100k | head/base | throughput | calibrated | 0.559 [0.559–0.559] | ±2.0% | **lower** |
| e3-mongo@100k | head/base | p50 | equal | 0.866 [0.599–1.237] | ±18.4% | within noise |
| e3-mongo@100k | head/base | p99 | equal | 0.677 [0.655–1.199] | ±9.1% | within noise |
| e3-mongo@1k | head/base | throughput | calibrated | 1.050 [1.050–1.051] | - | uncalibrated |
| e3-mongo@1k | head/base | p50 | equal | 1.308 [0.971–1.731] | - | uncalibrated |
| e3-mongo@1k | head/base | p99 | equal | 1.579 [0.968–1.515] | - | uncalibrated |
| e3-pg@100k | head/base | throughput | calibrated | 1.096 [1.096–1.096] | ±2.0% | **higher** |
| e3-pg@100k | head/base | p50 | equal | 0.770 [0.250–2.280] | ±37.3% | within noise |
| e3-pg@100k | head/base | p99 | equal | 0.904 [0.671–1.413] | ±39.5% | within noise |
| e3-pg@1k | head/base | throughput | calibrated | 0.928 [0.928–0.929] | - | uncalibrated |
| e3-pg@1k | head/base | p50 | equal | 1.685 [0.685–2.922] | - | uncalibrated |
| e3-pg@1k | head/base | p99 | equal | 3.455 [0.865–1.989] | - | uncalibrated |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| e3-mongo@100k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| e3-mongo@1k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| e3-pg@100k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 2-3 · node 0-1 |
| e3-pg@1k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 2-3 · node 0-1 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |
| e3-mongo@100k | base | 727.5/s | 2048 | 582.0/s | 80% |
| e3-mongo@100k | head | 406.6/s (probed again; first 85.1/s at concurrency 2) | 64 | 325.3/s | 80% |
| e3-mongo@1k | base | 917.1/s | 1024 | 309.5/s | 34% (**not sustained**) |
| e3-mongo@1k | head | 963.4/s | 1024 | 325.1/s | 34% |
| e3-pg@100k | base | 1116.3/s | 256 | 893.0/s | 80% |
| e3-pg@100k | head | 1223.0/s | 256 | 978.4/s | 80% |
| e3-pg@1k | base | 1351.4/s | 1024 | 456.1/s | 34% (**not sustained**) |
| e3-pg@1k | head | 1255.1/s | 512 | 423.6/s | 34% (**not sustained**) |

## Paired ratios

`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.

| Suite | b/a | Rounds | Saturation | Throughput | p50 | p95 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e3-mongo@100k | head/base | calibrated (unequal load) | 0.559 | 0.559 (0.559–0.559, n=3) | 0.160 (0.140–0.220, n=3) | 0.343 (0.273–0.357, n=3) | 0.412 (0.384–0.452, n=3) | - | 0.481 (0.454–0.523, n=3) | 1.546 (1.472–1.589, n=3) | 1.178 (1.153–1.178, n=3) |
| e3-mongo@100k | head/base | equal rate 325.3/s | 0.559 | 1.000 (1.000–1.000, n=2) | 0.960 (0.570–1.350, n=2) | 0.727 (0.500–0.954, n=2) | 0.690 (0.484–0.896, n=2) | - | 0.613 (0.456–0.770, n=2) | 1.000 (0.984–1.016, n=2) | 1.066 (1.030–1.102, n=2) |
| e3-mongo@1k | head/base | calibrated (unequal load) | 1.050 | 1.050 (1.050–1.050, n=3) | 1.555 (1.057–2.576, n=3) | 1.658 (1.387–22.609, n=3) | 1.730 (1.515–21.442, n=3) | - | 1.757 (1.536–27.898, n=3) | 0.936 (0.928–0.940, n=3) | 1.010 (0.990–1.027, n=3) |
| e3-mongo@1k | head/base | equal rate 309.5/s | 1.050 | 1.000 (1.000–1.000, n=2) | 1.486 (0.833–2.139, n=2) | 1.668 (0.784–2.553, n=2) | 1.756 (0.836–2.676, n=2) | - | 1.938 (1.048–2.828, n=2) | 1.006 (0.983–1.029, n=2) | 1.010 (0.970–1.051, n=2) |
| e3-pg@100k | head/base | calibrated (unequal load) | 1.096 | 1.096 (1.096–1.096, n=3) | 2.581 (1.053–5.757, n=3) | 1.464 (1.211–2.290, n=3) | 1.165 (1.036–1.888, n=3) | - | 1.077 (1.027–1.717, n=3) | 0.904 (0.902–0.921, n=3) | 1.038 (1.027–1.074, n=3) |
| e3-pg@100k | head/base | equal rate 893.0/s | 1.096 | 1.000 (1.000–1.000, n=2) | 1.002 (0.547–1.458, n=2) | 0.958 (0.731–1.186, n=2) | 0.887 (0.679–1.094, n=2) | - | 0.874 (0.648–1.100, n=2) | 1.002 (1.000–1.003, n=2) | 1.069 (1.051–1.087, n=2) |
| e3-pg@1k | head/base | calibrated (unequal load) | 0.929 | 0.929 (0.928–0.929, n=3) | 0.614 (0.413–1.436, n=3) | 1.351 (0.631–3.313, n=3) | 1.296 (0.673–3.775, n=3) | - | 1.314 (0.700–5.382, n=3) | 1.076 (1.073–1.086, n=3) | 1.086 (1.081–1.131, n=3) |
| e3-pg@1k | head/base | equal rate 423.6/s | 0.929 | 0.999 (0.999–1.000, n=2) | 1.595 (1.407–1.784, n=2) | 2.738 (2.500–2.976, n=2) | 3.370 (2.891–3.848, n=2) | - | 6.016 (5.942–6.091, n=2) | 0.999 (0.996–1.002, n=2) | 1.121 (1.099–1.144, n=2) |

---

### e3-mongo@100k

E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (55–71 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 727.5/s | 2048 | 582.0/s | 80% |
| head | 406.6/s (probed again; first 85.1/s at concurrency 2) | 64 | 325.3/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 582.0/s | 582.0/s | 11640 | 0 | 798.7 ms | 1310.7 ms | 1515.5 ms | n/a | 1736.7 ms | 0.69 cores | kafka 45% · mongo 96% | 0.38 of 1 | mongo 0.96 | 3.61 | 246.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 21 s | - |
| probe1-head | head | 325.3/s | 325.3/s | 6506 | 0 | 47.1 ms | 191.5 ms | 329.7 ms | n/a | 483.3 ms | 0.49 cores | kafka 41% · mongo 85% | 0.23 of 1 | mongo 0.85 | 5.36 | 310.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 582.0/s | 582.0/s | 23280 | 0 | 643.1 ms | 1187.8 ms | 1482.8 ms | n/a | 1695.7 ms | 0.54 cores | kafka 39% · mongo 90% | 0.17 of 1 | mongo 0.90 | 3.16 | 243.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| r1-head | head | 325.3/s | 325.3/s | 13011 | 0 | 102.9 ms | 407.6 ms | 610.3 ms | n/a | 815.1 ms | 0.43 cores | kafka 41% · mongo 79% | 0.12 of 1 | mongo 0.79 | 5.02 | 286.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 12 s | - |
| r2-head | head | 325.3/s | 325.3/s | 13011 | 0 | 147.5 ms | 479.2 ms | 831.5 ms | n/a | 1130.5 ms | 0.45 cores | kafka 40% · mongo 75% | 0.13 of 1 | mongo 0.75 | 4.94 | 281.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | - |
| r2-base | base | 582.0/s | 582.0/s | 23280 | 0 | 1056.8 ms | 1753.1 ms | 2162.7 ms | n/a | 2490.4 ms | 0.59 cores | kafka 42% · mongo 94% | 0.20 of 1 | mongo 0.94 | 3.36 | 238.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| r3-base | base | 582.0/s | 582.0/s | 23280 | 0 | 606.2 ms | 1261.6 ms | 1523.7 ms | n/a | 1744.9 ms | 0.54 cores | kafka 41% · mongo 90% | 0.19 of 1 | mongo 0.90 | 3.19 | 246.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| r3-head | head | 325.3/s | 325.3/s | 13011 | 0 | 133.1 ms | 450.6 ms | 688.1 ms | n/a | 913.4 ms | 0.44 cores | kafka 41% · mongo 76% | 0.17 of 1 | mongo 0.76 | 4.94 | 284.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 20 s | - |
| e1-base | base | 325.3/s | 325.3/s | 13011 | 0 | 62.2 ms | 444.4 ms | 667.6 ms | n/a | 925.7 ms | 0.45 cores | kafka 43% · mongo 80% | 0.18 of 1 | mongo 0.80 | 5.15 | 291.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| e1-head | head | 325.3/s | 325.3/s | 13011 | 0 | 84.0 ms | 423.9 ms | 598.0 ms | n/a | 712.7 ms | 0.45 cores | kafka 41% · mongo 79% | 0.12 of 1 | mongo 0.79 | 5.07 | 300.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | - |
| e2-head | head | 325.3/s | 325.3/s | 13011 | 0 | 58.1 ms | 242.7 ms | 364.5 ms | n/a | 503.8 ms | 0.44 cores | kafka 40% · mongo 81% | 0.11 of 1 | mongo 0.81 | 5.07 | 302.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e2-base | base | 325.3/s | 325.3/s | 13011 | 0 | 101.9 ms | 485.4 ms | 753.7 ms | n/a | 1105.9 ms | 0.45 cores | kafka 42% · mongo 76% | 0.13 of 1 | mongo 0.76 | 4.99 | 274.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 15 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.559 | 0.160 | 0.343 | 0.412 | - | 0.481 | 1.589 | 1.178 |
| 2 | 0.559 | 0.140 | 0.273 | 0.384 | - | 0.454 | 1.472 | 1.178 |
| 3 | 0.559 | 0.220 | 0.357 | 0.452 | - | 0.523 | 1.546 | 1.153 |

Pairs at one rate for both arms, 325.3/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.350 | 0.954 | 0.896 | - | 0.770 | 0.984 | 1.030 |
| 2 | 1.000 | 0.570 | 0.500 | 0.484 | - | 0.456 | 1.016 | 1.102 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@100k | head against base | cost | **more allocation** | head allocates ×1.07 the bytes per operation of base (median of the equal-rate pairs) |
| e3-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +47.0% (±25.0%) |
| e3-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +26.7% (±25.0%) |
| e3-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +53.0% (±25.0%) |
| e3-mongo@100k | e1-base | steady | **p99 > 10× p50** | p50 62.2 ms, p99 667.6 ms |
| e3-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +105.3% (±25.0%) |


### e3-mongo@1k

E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.

E3, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 917.1/s | 1024 | 309.5/s | 34% (**not sustained**) |
| head | 963.4/s | 1024 | 325.1/s | 34% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 733.7/s | 370.2/s | 7404 | 7269 | 4358.1 ms | 8224.8 ms | 9895.9 ms | n/a | 10551.3 ms | 1.00 cores | kafka 55% · mongo 50% | 0.29 of 1 | server 1.00 | 5.52 | 135.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | generator-overload 7116 · http-504-wow.wait.timeout 153 |
| probe2-base | base | 550.3/s | 386.9/s | 7737 | 3269 | 4177.9 ms | 7962.6 ms | 9568.3 ms | n/a | 10289.2 ms | 0.99 cores | kafka 54% · mongo 51% | 0.31 of 1 | server 0.99 | 5.28 | 192.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | generator-overload 3157 · http-504-wow.wait.timeout 112 |
| probe3-base | base | 412.7/s | 294.5/s | 5890 | 2364 | 4980.7 ms | 8978.4 ms | 10223.6 ms | n/a | 17432.6 ms | 1.00 cores | kafka 58% · mongo 46% | 0.30 of 1 | server 1.00 | 6.89 | 198.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | generator-overload 1924 · http-504-wow.wait.timeout 440 |
| probe4-base | base | 309.5/s | 284.6/s | 5692 | 498 | 5210.1 ms | 8912.9 ms | 10158.1 ms | n/a | 10944.5 ms | 0.99 cores | kafka 61% · mongo 47% | 0.33 of 1 | server 0.99 | 7.31 | 252.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | http-504-wow.wait.timeout 255 · generator-overload 243 |
| probe1-head | head | 770.7/s | 393.3/s | 7865 | 7549 | 4128.8 ms | 8126.5 ms | 9633.8 ms | n/a | 10223.6 ms | 0.99 cores | kafka 57% · mongo 51% | 0.31 of 1 | server 0.99 | 5.28 | 144.6 KB | - | datasets 8 of 8 · default 12–25 of 50, lowered 1, pressed 9 s | generator-overload 7392 · http-504-wow.wait.timeout 157 |
| probe2-head | head | 578.0/s | 419.4/s | 8388 | 3172 | 3850.2 ms | 7569.4 ms | 9306.1 ms | n/a | 9961.5 ms | 1.00 cores | kafka 53% · mongo 50% | 0.30 of 1 | server 1.00 | 4.84 | 199.5 KB | - | datasets 8 of 8 · default 25–27 of 50, lowered 0, pressed 8 s | generator-overload 3102 · http-504-wow.wait.timeout 70 |
| probe3-head | head | 433.5/s | 419.4/s | 8387 | 283 | 3113.0 ms | 6684.7 ms | 8978.4 ms | n/a | 9961.5 ms | 0.99 cores | kafka 48% · mongo 51% | 0.29 of 1 | server 0.99 | 4.71 | 281.4 KB | - | datasets 8 of 8 · default 12–25 of 50, lowered 1, pressed 7 s | generator-overload 260 · http-504-wow.wait.timeout 23 |
| probe4-head | head | 325.1/s | 325.1/s | 6502 | 0 | 499.7 ms | 2359.3 ms | 3080.2 ms | n/a | 4046.8 ms | 1.00 cores | kafka 50% · mongo 62% | 0.26 of 1 | server 1.00 | 6.49 | 326.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 309.5/s | 309.5/s | 12380 | 0 | 49.2 ms | 254.0 ms | 333.8 ms | n/a | 489.5 ms | 1.00 cores | kafka 47% · mongo 77% | 0.17 of 1 | server 1.00 | 7.20 | 320.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r1-head | head | 325.1/s | 325.1/s | 13003 | 0 | 52.0 ms | 352.3 ms | 577.5 ms | n/a | 860.2 ms | 1.00 cores | kafka 42% · mongo 75% | 0.16 of 1 | server 1.00 | 6.69 | 328.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r2-head | head | 325.1/s | 325.1/s | 13003 | 0 | 58.1 ms | 203.8 ms | 301.1 ms | n/a | 522.2 ms | 1.00 cores | kafka 44% · mongo 75% | 0.16 of 1 | server 1.00 | 6.74 | 321.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r2-base | base | 309.5/s | 309.5/s | 12380 | 0 | 37.4 ms | 122.9 ms | 198.7 ms | n/a | 340.0 ms | 0.99 cores | kafka 45% · mongo 79% | 0.15 of 1 | server 0.99 | 7.20 | 324.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r3-base | base | 309.5/s | 309.5/s | 12380 | 0 | 26.2 ms | 94.2 ms | 150.5 ms | n/a | 239.6 ms | 0.97 cores | kafka 43% · mongo 80% | 0.15 of 1 | server 0.97 | 7.09 | 338.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r3-head | head | 325.1/s | 325.1/s | 13003 | 0 | 67.6 ms | 2129.9 ms | 3227.6 ms | n/a | 6684.7 ms | 1.00 cores | kafka 45% · mongo 72% | 0.18 of 1 | server 1.00 | 6.67 | 341.2 KB | - | datasets 8 of 8 · default 9–25 of 50, lowered 0, pressed 0 s | - |
| e1-base | base | 309.5/s | 309.5/s | 12380 | 0 | 32.3 ms | 118.3 ms | 205.8 ms | n/a | 299.0 ms | 1.00 cores | kafka 44% · mongo 78% | 0.17 of 1 | server 1.00 | 7.18 | 331.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-head | head | 309.5/s | 309.5/s | 12380 | 0 | 26.9 ms | 92.7 ms | 172.0 ms | n/a | 313.3 ms | 0.98 cores | kafka 42% · mongo 78% | 0.15 of 1 | server 0.98 | 7.06 | 348.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-head | head | 309.5/s | 309.5/s | 12380 | 0 | 39.4 ms | 222.2 ms | 372.7 ms | n/a | 622.6 ms | 1.00 cores | kafka 41% · mongo 76% | 0.15 of 1 | server 1.00 | 7.02 | 339.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-base | base | 309.5/s | 309.5/s | 12380 | 0 | 18.4 ms | 87.0 ms | 139.3 ms | n/a | 220.2 ms | 0.90 cores | kafka 41% · mongo 79% | 0.15 of 1 | server 0.90 | 6.82 | 350.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.050 | 1.057 | 1.387 | 1.730 | - | 1.757 | 0.928 | 1.027 |
| 2 | 1.050 | 1.555 | 1.658 | 1.515 | - | 1.536 | 0.936 | 0.990 |
| 3 | 1.050 | 2.576 | 22.609 | 21.442 | - | 27.898 | 0.940 | 1.010 |

Pairs at one rate for both arms, 309.5/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.833 | 0.784 | 0.836 | - | 1.048 | 0.983 | 1.051 |
| 2 | 1.000 | 2.139 | 2.553 | 2.676 | - | 2.828 | 1.029 | 0.970 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -61.9% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -60.1% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 52.0 ms, p99 577.5 ms |
| e3-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -60.1% (±25.0%) |
| e3-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -28.1% (±25.0%) |
| e3-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -71.6% (±25.0%) |
| e3-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 67.6 ms, p99 3227.6 ms |
| e3-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.4% (±25.0%) |
| e3-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -25.4% (±25.0%) |
| e3-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -53.8% (±25.0%) |


### e3-pg@100k

E3 saga chain on PostgreSQL: head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (36–44 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1116.3/s | 256 | 893.0/s | 80% |
| head | 1223.0/s | 256 | 978.4/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 893.0/s | 893.0/s | 17860 | 0 | 540.7 ms | 1368.1 ms | 1556.5 ms | n/a | 1777.7 ms | 0.85 cores | kafka 50% · postgres 63% | 0.33 of 2 | server 0.85 | 2.21 | 228.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | - |
| probe1-head | head | 978.4/s | 977.5/s | 19551 | 17 | 667.6 ms | 2031.6 ms | 2293.8 ms | n/a | 2588.7 ms | 0.86 cores | kafka 46% · postgres 62% | 0.28 of 2 | server 0.86 | 1.98 | 244.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 15 s | generator-overload 17 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 893.0/s | 893.0/s | 35720 | 0 | 151.6 ms | 626.7 ms | 942.1 ms | n/a | 1163.3 ms | 0.75 cores | kafka 47% · postgres 75% | 0.22 of 2 | postgres 0.75 | 2.20 | 224.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 11 s | - |
| r1-head | head | 978.4/s | 978.4/s | 39136 | 0 | 391.2 ms | 917.5 ms | 1097.7 ms | n/a | 1253.4 ms | 0.79 cores | kafka 45% · postgres 71% | 0.25 of 2 | server 0.79 | 1.99 | 233.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 17 s | - |
| r2-head | head | 978.4/s | 978.4/s | 39136 | 0 | 278.5 ms | 679.9 ms | 761.9 ms | n/a | 843.8 ms | 0.78 cores | kafka 43% · postgres 74% | 0.25 of 2 | server 0.78 | 1.98 | 233.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 10 s | - |
| r2-base | base | 893.0/s | 893.0/s | 35720 | 0 | 48.4 ms | 297.0 ms | 403.5 ms | n/a | 491.5 ms | 0.77 cores | kafka 44% · postgres 76% | 0.20 of 2 | server 0.77 | 2.20 | 227.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| r3-base | base | 893.0/s | 893.0/s | 35720 | 0 | 191.5 ms | 622.6 ms | 1019.9 ms | n/a | 1220.6 ms | 0.76 cores | kafka 45% · postgres 75% | 0.23 of 2 | server 0.76 | 2.19 | 223.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | - |
| r3-head | head | 978.4/s | 978.4/s | 39136 | 0 | 201.7 ms | 753.7 ms | 1056.8 ms | n/a | 1253.4 ms | 0.80 cores | kafka 44% · postgres 74% | 0.24 of 2 | server 0.80 | 2.02 | 239.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | - |
| e1-base | base | 893.0/s | 893.0/s | 35720 | 0 | 64.3 ms | 452.6 ms | 868.4 ms | n/a | 1028.1 ms | 0.76 cores | kafka 44% · postgres 77% | 0.22 of 2 | postgres 0.77 | 2.21 | 231.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | - |
| e1-head | head | 893.0/s | 893.0/s | 35720 | 0 | 93.7 ms | 536.6 ms | 950.3 ms | n/a | 1130.5 ms | 0.79 cores | kafka 42% · postgres 77% | 0.22 of 2 | server 0.79 | 2.21 | 242.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | - |
| e2-head | head | 893.0/s | 893.0/s | 35720 | 0 | 108.0 ms | 428.0 ms | 497.7 ms | n/a | 557.1 ms | 0.78 cores | kafka 44% · postgres 74% | 0.23 of 2 | server 0.78 | 2.20 | 243.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| e2-base | base | 893.0/s | 893.0/s | 35720 | 0 | 197.6 ms | 585.7 ms | 733.2 ms | n/a | 860.2 ms | 0.75 cores | kafka 46% · postgres 74% | 0.22 of 2 | server 0.75 | 2.19 | 223.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 17 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.096 | 2.581 | 1.464 | 1.165 | - | 1.077 | 0.904 | 1.038 |
| 2 | 1.096 | 5.757 | 2.290 | 1.888 | - | 1.717 | 0.902 | 1.027 |
| 3 | 1.096 | 1.053 | 1.211 | 1.036 | - | 1.027 | 0.921 | 1.074 |

Pairs at one rate for both arms, 893.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.458 | 1.186 | 1.094 | - | 1.100 | 1.000 | 1.051 |
| 2 | 1.000 | 0.547 | 0.731 | 0.679 | - | 0.648 | 1.003 | 1.087 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-pg@100k | head against base | cost | **more allocation** | head allocates ×1.07 the bytes per operation of base (median of the equal-rate pairs) |
| e3-pg@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -33.4% (±25.0%) |
| e3-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +44.7% (±25.0%) |
| e3-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -30.0% (±25.0%) |
| e3-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 64.3 ms, p99 868.4 ms |
| e3-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +131.2% (±25.0%) |
| e3-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 93.7 ms, p99 950.3 ms |
| e3-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +31.6% (±25.0%) |


### e3-pg@1k

E3 saga chain on PostgreSQL: head against baseline.

E3, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1351.4/s | 1024 | 456.1/s | 34% (**not sustained**) |
| head | 1255.1/s | 512 | 423.6/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1081.1/s | 504.3/s | 10086 | 11535 | 3080.2 ms | 6586.4 ms | 8847.4 ms | n/a | 10223.6 ms | 1.18 cores | kafka 59% · postgres 25% | 0.29 of 2 | server 1.18 | 4.00 | 114.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | generator-overload 11491 · http-504-wow.wait.timeout 44 |
| probe2-base | base | 810.8/s | 560.4/s | 11208 | 5008 | 2916.4 ms | 5931.0 ms | 7831.6 ms | n/a | 9502.7 ms | 1.19 cores | kafka 54% · postgres 27% | 0.27 of 2 | server 1.19 | 3.58 | 168.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | generator-overload 4997 · http-504-wow.wait.timeout 11 |
| probe3-base | base | 608.1/s | 495.3/s | 9905 | 2257 | 2785.3 ms | 6127.6 ms | 8060.9 ms | n/a | 10289.2 ms | 1.21 cores | kafka 54% · postgres 26% | 0.26 of 2 | server 1.21 | 4.07 | 210.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | generator-overload 2227 · http-504-wow.wait.timeout 30 |
| probe4-base | base | 456.1/s | 403.6/s | 8073 | 1049 | 3194.9 ms | 6979.6 ms | 8781.8 ms | n/a | 10223.6 ms | 1.17 cores | kafka 61% · postgres 22% | 0.27 of 2 | server 1.17 | 4.96 | 227.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | generator-overload 1012 · http-504-wow.wait.timeout 37 |
| probe1-head | head | 1004.1/s | 361.4/s | 7229 | 12853 | 3735.6 ms | 7897.1 ms | 9764.9 ms | n/a | 10857.8 ms | 1.19 cores | kafka 61% · postgres 20% | 0.28 of 2 | server 1.19 | 5.56 | 94.7 KB | - | datasets 8 of 8 · default 1–5 of 5, lowered 1, pressed 11 s | generator-overload 12323 · http-504-wow.wait.timeout 530 |
| probe2-head | head | 753.1/s | 506.8/s | 10136 | 4926 | 2621.4 ms | 6520.8 ms | 8978.4 ms | n/a | 10223.6 ms | 1.20 cores | kafka 56% · postgres 25% | 0.30 of 2 | server 1.20 | 3.97 | 174.7 KB | - | datasets 8 of 8 · default 1–5 of 5, lowered 1, pressed 7 s | generator-overload 4558 · http-504-wow.wait.timeout 368 |
| probe3-head | head | 564.8/s | 469.9/s | 9397 | 1899 | 2375.7 ms | 6357.0 ms | 9044.0 ms | n/a | 10223.6 ms | 1.20 cores | kafka 55% · postgres 26% | 0.28 of 2 | server 1.20 | 4.28 | 226.2 KB | - | datasets 8 of 8 · default 1–5 of 5, lowered 1, pressed 10 s | generator-overload 1591 · http-504-wow.wait.timeout 308 |
| probe4-head | head | 423.6/s | 388.3/s | 7765 | 707 | 2179.1 ms | 6553.6 ms | 9109.5 ms | n/a | 10289.2 ms | 1.20 cores | kafka 53% · postgres 27% | 0.21 of 2 | server 1.20 | 5.16 | 278.8 KB | - | datasets 8 of 8 · default 2–5 of 5, lowered 0, pressed 6 s | generator-overload 608 · http-504-wow.wait.timeout 99 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 456.1/s | 456.1/s | 18244 | 0 | 69.6 ms | 548.9 ms | 954.4 ms | n/a | 1515.5 ms | 1.04 cores | kafka 46% · postgres 51% | 0.13 of 2 | server 1.04 | 4.40 | 247.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r1-head | head | 423.6/s | 423.6/s | 16944 | 0 | 42.8 ms | 741.4 ms | 1237.0 ms | n/a | 1990.7 ms | 1.04 cores | kafka 44% · postgres 52% | 0.13 of 2 | server 1.04 | 4.73 | 268.1 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | - |
| r2-head | head | 423.6/s | 423.2/s | 16927 | 17 | 177.2 ms | 2211.8 ms | 4390.9 ms | n/a | 9568.3 ms | 1.09 cores | kafka 46% · postgres 46% | 0.14 of 2 | server 1.09 | 4.74 | 274.2 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | http-504-wow.wait.timeout 17 |
| r2-base | base | 456.1/s | 456.1/s | 18244 | 0 | 123.4 ms | 667.6 ms | 1163.3 ms | n/a | 1777.7 ms | 1.07 cores | kafka 46% · postgres 48% | 0.14 of 2 | server 1.07 | 4.40 | 242.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r3-base | base | 456.1/s | 456.1/s | 18244 | 0 | 376.8 ms | 1908.7 ms | 2752.5 ms | n/a | 3981.3 ms | 1.05 cores | kafka 51% · postgres 44% | 0.16 of 2 | server 1.05 | 4.37 | 242.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r3-head | head | 423.6/s | 423.6/s | 16944 | 0 | 155.6 ms | 1204.2 ms | 1851.4 ms | n/a | 2785.3 ms | 1.07 cores | kafka 47% · postgres 47% | 0.14 of 2 | server 1.07 | 4.74 | 261.7 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 1 s | - |
| e1-base | base | 423.6/s | 423.6/s | 16944 | 0 | 90.6 ms | 557.1 ms | 753.7 ms | n/a | 987.1 ms | 1.04 cores | kafka 47% · postgres 49% | 0.14 of 2 | server 1.04 | 4.72 | 246.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-head | head | 423.6/s | 423.6/s | 16944 | 0 | 127.5 ms | 1392.6 ms | 2179.1 ms | n/a | 5865.5 ms | 1.06 cores | kafka 46% · postgres 49% | 0.14 of 2 | server 1.06 | 4.73 | 270.8 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | - |
| e2-head | head | 423.6/s | 423.1/s | 16923 | 21 | 456.7 ms | 2523.1 ms | 4161.5 ms | n/a | 8781.8 ms | 1.10 cores | kafka 46% · postgres 44% | 0.15 of 2 | server 1.10 | 4.72 | 270.6 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | http-504-wow.wait.timeout 21 |
| e2-base | base | 423.6/s | 423.6/s | 16944 | 0 | 256.0 ms | 847.9 ms | 1081.3 ms | n/a | 1441.8 ms | 1.06 cores | kafka 50% · postgres 45% | 0.14 of 2 | server 1.06 | 4.74 | 236.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.929 | 0.614 | 1.351 | 1.296 | - | 1.314 | 1.073 | 1.086 |
| 2 | 0.928 | 1.436 | 3.313 | 3.775 | - | 5.382 | 1.076 | 1.131 |
| 3 | 0.929 | 0.413 | 0.631 | 0.673 | - | 0.700 | 1.086 | 1.081 |

Pairs at one rate for both arms, 423.6/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.407 | 2.500 | 2.891 | - | 5.942 | 1.002 | 1.099 |
| 2 | 0.999 | 1.784 | 2.976 | 3.848 | - | 6.091 | 0.996 | 1.144 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-pg@1k | head against base | cost | **more allocation** | head allocates ×1.12 the bytes per operation of base (median of the equal-rate pairs) |
| e3-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -78.4% (±25.0%) |
| e3-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 69.6 ms, p99 954.4 ms |
| e3-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -80.5% (±25.0%) |
| e3-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 42.8 ms, p99 1237.0 ms |
| e3-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -92.1% (±25.0%) |
| e3-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 177.2 ms, p99 4390.9 ms |
| e3-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -78.3% (±25.0%) |
| e3-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -72.2% (±25.0%) |
| e3-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -77.4% (±25.0%) |
| e3-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 155.6 ms, p99 1851.4 ms |
| e3-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -88.0% (±25.0%) |
| e3-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -82.2% (±25.0%) |
| e3-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 127.5 ms, p99 2179.1 ms |
| e3-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -87.2% (±25.0%) |
| e3-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -70.7% (±25.0%) |



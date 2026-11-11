# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `4e20d0c22ddaf2c4d1a63853f360f94e171933a7` 4e20d0c22ddaf2c4d1a63853f360f94e171933a7; V10 baseline: `d781d71441847d8637586771d937b6c5302a7ab0` d781d71441847d8637586771d937b6c5302a7ab0; harness and load generator from head.

Tier: custom; coverage: e1-mongo-uniform@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -23.7% (±19.9%) |
| e1-mongo-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +42.3% (±19.9%) |
| e1-mongo-uniform@100k | r4-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -30.6% (±19.9%) |
| e1-mongo-uniform@100k | r5-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +41.9% (±19.9%) |
| e1-mongo-uniform@100k | r5-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +41.5% (±19.9%) |
| e1-mongo-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -46.9% (±19.9%) |
| e1-mongo-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +40.3% (±19.9%) |
| e1-mongo-uniform@100k | e3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -34.5% (±19.9%) |

## Significance

Beyond noise: 1 of 3 ratios; 1 of 1 suites have an A/A noise threshold (test/benchmarks/noise.json). A ratio counts when its 95% bootstrap interval (over the measured seconds) clears the suite's threshold; without one it is `uncalibrated`.

| Suite | b/a | Metric | Rounds | Ratio [95% CI] | Noise | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | throughput | calibrated | 0.858 [0.858–0.858] | ±2.0% | **lower** |
| e1-mongo-uniform@100k | head/base | p50 | equal | 0.927 [0.725–1.290] | ±19.9% | within noise |
| e1-mongo-uniform@100k | head/base | p99 | equal | 1.177 [0.820–1.170] | ±12.1% | within noise |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | base | 1621.3/s | 2048 | 1297.0/s | 80% |
| e1-mongo-uniform@100k | head | 1391.1/s | 1024 | 1112.9/s | 80% |

## Paired ratios

`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.

| Suite | b/a | Rounds | Saturation | Throughput | p50 | p95 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | calibrated (unequal load) | 0.858 | 0.858 (0.858–0.858, n=5) | 0.544 (0.481–0.761, n=5) | 0.730 (0.587–1.136, n=5) | 0.791 (0.590–1.244, n=5) | - | 0.847 (0.675–1.359, n=5) | 1.124 (1.120–1.150, n=5) | 1.018 (1.001–1.033, n=5) |
| e1-mongo-uniform@100k | head/base | equal rate 1112.9/s | 0.858 | 1.000 (1.000–1.000, n=2) | 1.050 (0.980–1.120, n=2) | 1.211 (1.004–1.417, n=2) | 1.373 (0.865–1.882, n=2) | - | 1.273 (0.821–1.725, n=2) | 1.009 (1.001–1.018, n=2) | 0.975 (0.967–0.982, n=2) |

---

### e1-mongo-uniform@100k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 4 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (68–75 s), then waited until the nodes were quiet again (4–181 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1621.3/s | 2048 | 1297.0/s | 80% |
| head | 1391.1/s | 1024 | 1112.9/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1297.0/s | 1297.0/s | 25940 | 0 | 272.4 ms | 888.8 ms | 1056.8 ms | n/a | 1220.6 ms | 0.77 cores | kafka 51% · mongo 99% | 0.69 of 1 | mongo 0.99 | 1.75 | 97.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 15 s | - |
| probe1-head | head | 1112.9/s | 1112.9/s | 22258 | 0 | 128.5 ms | 452.6 ms | 573.4 ms | n/a | 647.2 ms | 0.72 cores | kafka 50% · mongo 99% | 0.62 of 1 | mongo 0.99 | 1.99 | 100.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1297.0/s | 1297.0/s | 51880 | 0 | 106.0 ms | 305.2 ms | 430.1 ms | n/a | 585.7 ms | 0.75 cores | kafka 50% · mongo 100% | 0.42 of 1 | mongo 1.00 | 1.73 | 99.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r1-head | head | 1112.9/s | 1112.9/s | 44516 | 0 | 52.5 ms | 179.2 ms | 254.0 ms | n/a | 395.3 ms | 0.67 cores | kafka 50% · mongo 99% | 0.37 of 1 | mongo 0.99 | 1.94 | 102.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |
| r2-head | head | 1112.9/s | 1112.9/s | 44516 | 0 | 78.3 ms | 342.0 ms | 512.0 ms | n/a | 729.1 ms | 0.69 cores | kafka 48% · mongo 99% | 0.37 of 1 | mongo 0.99 | 1.94 | 101.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| r2-base | base | 1297.0/s | 1297.0/s | 51880 | 0 | 102.9 ms | 301.1 ms | 411.6 ms | n/a | 536.6 ms | 0.74 cores | kafka 49% · mongo 100% | 0.41 of 1 | mongo 1.00 | 1.73 | 99.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| r3-base | base | 1297.0/s | 1297.0/s | 51880 | 0 | 106.0 ms | 288.8 ms | 383.0 ms | n/a | 501.8 ms | 0.74 cores | kafka 47% · mongo 100% | 0.42 of 1 | mongo 1.00 | 1.70 | 99.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| r3-head | head | 1112.9/s | 1112.9/s | 44516 | 0 | 50.9 ms | 210.9 ms | 303.1 ms | n/a | 444.4 ms | 0.69 cores | kafka 49% · mongo 99% | 0.36 of 1 | mongo 0.99 | 1.95 | 102.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r4-head | head | 1112.9/s | 1112.9/s | 44516 | 0 | 75.3 ms | 244.7 ms | 354.3 ms | n/a | 481.3 ms | 0.69 cores | kafka 51% · mongo 99% | 0.38 of 1 | mongo 0.99 | 1.98 | 100.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| r4-base | base | 1297.0/s | 1297.0/s | 51880 | 0 | 138.2 ms | 370.7 ms | 505.9 ms | n/a | 659.5 ms | 0.78 cores | kafka 51% · mongo 99% | 0.43 of 1 | mongo 0.99 | 1.76 | 99.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| r5-base | base | 1297.0/s | 1297.0/s | 51880 | 0 | 174.1 ms | 491.5 ms | 630.8 ms | n/a | 749.6 ms | 0.78 cores | kafka 49% · mongo 99% | 0.43 of 1 | mongo 0.99 | 1.74 | 98.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 12 s | - |
| r5-head | head | 1112.9/s | 1112.9/s | 44516 | 0 | 113.7 ms | 395.3 ms | 520.2 ms | n/a | 634.9 ms | 0.72 cores | kafka 50% · mongo 100% | 0.39 of 1 | mongo 1.00 | 2.00 | 98.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 12 s | - |
| e1-base | base | 1112.9/s | 1112.9/s | 44516 | 0 | 59.9 ms | 210.9 ms | 311.3 ms | n/a | 491.5 ms | 0.69 cores | kafka 47% · mongo 99% | 0.36 of 1 | mongo 0.99 | 1.93 | 104.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e1-head | head | 1112.9/s | 1112.9/s | 44516 | 0 | 67.1 ms | 299.0 ms | 585.7 ms | n/a | 847.9 ms | 0.72 cores | kafka 47% · mongo 100% | 0.38 of 1 | mongo 1.00 | 1.96 | 101.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e2-head | head | 1112.9/s | 1112.9/s | 44516 | 0 | 75.3 ms | 254.0 ms | 340.0 ms | n/a | 460.8 ms | 0.71 cores | kafka 50% · mongo 99% | 0.39 of 1 | mongo 0.99 | 1.97 | 101.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| e2-base | base | 1112.9/s | 1112.9/s | 44516 | 0 | 76.8 ms | 252.9 ms | 393.2 ms | n/a | 561.2 ms | 0.71 cores | kafka 48% · mongo 100% | 0.38 of 1 | mongo 1.00 | 1.97 | 103.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| e3-base | base | 1112.9/s | 1112.9/s | 44516 | 0 | 83.5 ms | 313.3 ms | 413.7 ms | n/a | 518.1 ms | 0.75 cores | kafka 49% · mongo 100% | 0.38 of 1 | mongo 1.00 | 2.01 | 101.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.858 | 0.495 | 0.587 | 0.590 | - | 0.675 | 1.120 | 1.033 |
| 2 | 0.858 | 0.761 | 1.136 | 1.244 | - | 1.359 | 1.124 | 1.018 |
| 3 | 0.858 | 0.481 | 0.730 | 0.791 | - | 0.886 | 1.144 | 1.033 |
| 4 | 0.858 | 0.544 | 0.660 | 0.700 | - | 0.730 | 1.121 | 1.018 |
| 5 | 0.858 | 0.653 | 0.804 | 0.825 | - | 0.847 | 1.150 | 1.001 |

Pairs at one rate for both arms, 1112.9/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.120 | 1.417 | 1.882 | - | 1.725 | 1.018 | 0.967 |
| 2 | 1.000 | 0.980 | 1.004 | 0.865 | - | 0.821 | 1.001 | 0.982 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +42.3% (±25.0%) |
| e1-mongo-uniform@100k | r4-base | steady | **unstable** | second half against first, beyond the default limits: p50 -30.6% (±25.0%) |
| e1-mongo-uniform@100k | r5-base | steady | **unstable** | second half against first, beyond the default limits: p50 +41.9% (±25.0%) |
| e1-mongo-uniform@100k | r5-head | steady | **unstable** | second half against first, beyond the default limits: p50 +41.5% (±25.0%) |
| e1-mongo-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -46.9% (±25.0%) |
| e1-mongo-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +40.3% (±25.0%) |
| e1-mongo-uniform@100k | e3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -34.5% (±25.0%) |



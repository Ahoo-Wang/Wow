# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `d781d71441847d8637586771d937b6c5302a7ab0` d781d71441847d8637586771d937b6c5302a7ab0; V10 baseline: `4e20d0c22ddaf2c4d1a63853f360f94e171933a7` 4e20d0c22ddaf2c4d1a63853f360f94e171933a7; harness and load generator from head.

Tier: custom; coverage: e1-mongo-uniform@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +143.1% (±19.9%) |
| e1-mongo-uniform@100k | r1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +25.0% (±19.9%) |
| e1-mongo-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +180.9% (±19.9%) |
| e1-mongo-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +26.8% (±19.9%) |
| e1-mongo-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -26.1% (±19.9%) |
| e1-mongo-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +57.7% (±19.9%) |

## Significance

Beyond noise: 1 of 3 ratios; 1 of 1 suites have an A/A noise threshold (test/benchmarks/noise.json). A ratio counts when its 95% bootstrap interval (over the measured seconds) clears the suite's threshold; without one it is `uncalibrated`.

| Suite | b/a | Metric | Rounds | Ratio [95% CI] | Noise | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | throughput | calibrated | 0.890 [0.889–0.892] | ±2.0% | **lower** |
| e1-mongo-uniform@100k | head/base | p50 | equal | 0.959 [0.588–1.429] | ±19.9% | within noise |
| e1-mongo-uniform@100k | head/base | p99 | equal | 1.123 [0.876–1.268] | ±12.1% | within noise |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | base | 2946.0/s | 2048 | 2356.8/s | 80% |
| e1-mongo-uniform@100k | head | 2620.0/s | 1024 | 2096.0/s | 80% |

## Paired ratios

`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.

| Suite | b/a | Rounds | Saturation | Throughput | p50 | p95 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | calibrated (unequal load) | 0.889 | 0.889 (0.889–0.892, n=3) | 0.348 (0.304–0.469, n=3) | 0.489 (0.344–0.716, n=3) | 0.557 (0.357–0.730, n=3) | - | 0.544 (0.329–0.745, n=3) | 1.107 (1.101–1.107, n=3) | 1.054 (1.038–1.054, n=3) |
| e1-mongo-uniform@100k | head/base | equal rate 2096.0/s | 0.889 | 1.000 (1.000–1.000, n=2) | 0.944 (0.858–1.031, n=2) | 1.004 (0.909–1.099, n=2) | 1.120 (1.022–1.218, n=2) | - | 1.127 (1.046–1.208, n=2) | 1.009 (1.002–1.016, n=2) | 1.003 (1.003–1.004, n=2) |

---

### e1-mongo-uniform@100k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (34–42 s), then waited until the nodes were quiet again (3–4 s).

- `head`: wow.access.walk-rate=1, wow.access.walk-batch=1

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2946.0/s | 2048 | 2356.8/s | 80% |
| head | 2620.0/s | 1024 | 2096.0/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 2356.8/s | 2356.8/s | 47136 | 0 | 192.5 ms | 651.3 ms | 852.0 ms | n/a | 1015.8 ms | 0.73 cores | kafka 43% · mongo 97% | 0.51 of 1 | mongo 0.97 | 0.91 | 93.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 16 s | - |
| probe1-head | head | 2096.0/s | 2096.0/s | 41920 | 0 | 76.8 ms | 374.8 ms | 516.1 ms | n/a | 684.0 ms | 0.70 cores | kafka 40% · mongo 97% | 0.48 of 1 | mongo 0.97 | 0.99 | 97.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 2356.8/s | 2356.8/s | 94272 | 0 | 169.0 ms | 536.6 ms | 716.8 ms | n/a | 888.8 ms | 0.71 cores | kafka 39% · mongo 97% | 0.44 of 1 | mongo 0.97 | 0.88 | 94.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 25 s | - |
| r1-head | head | 2096.0/s | 2096.0/s | 83840 | 0 | 58.9 ms | 262.1 ms | 399.4 ms | n/a | 483.3 ms | 0.69 cores | kafka 37% · mongo 97% | 0.41 of 1 | mongo 0.97 | 0.97 | 99.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 12 s | - |
| r2-head | head | 2096.0/s | 2096.0/s | 83840 | 0 | 69.6 ms | 239.6 ms | 344.1 ms | n/a | 442.4 ms | 0.68 cores | kafka 38% · mongo 96% | 0.41 of 1 | mongo 0.96 | 0.96 | 98.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| r2-base | base | 2356.8/s | 2350.8/s | 94034 | 238 | 229.4 ms | 696.3 ms | 962.6 ms | n/a | 1343.5 ms | 0.69 cores | kafka 38% · mongo 98% | 0.46 of 1 | mongo 0.98 | 0.87 | 93.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 26 s | generator-overload 238 |
| r3-base | base | 2356.8/s | 2356.8/s | 94272 | 0 | 133.1 ms | 397.3 ms | 659.5 ms | n/a | 868.4 ms | 0.68 cores | kafka 37% · mongo 97% | 0.44 of 1 | mongo 0.97 | 0.86 | 95.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 18 s | - |
| r3-head | head | 2096.0/s | 2096.0/s | 83840 | 0 | 62.5 ms | 284.7 ms | 481.3 ms | n/a | 647.2 ms | 0.65 cores | kafka 37% · mongo 97% | 0.40 of 1 | mongo 0.97 | 0.95 | 98.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| e1-base | base | 2096.0/s | 2096.0/s | 83840 | 0 | 70.1 ms | 280.6 ms | 370.7 ms | n/a | 532.5 ms | 0.66 cores | kafka 37% · mongo 97% | 0.40 of 1 | mongo 0.97 | 0.96 | 98.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | - |
| e1-head | head | 2096.0/s | 2096.0/s | 83840 | 0 | 60.2 ms | 255.0 ms | 378.9 ms | n/a | 557.1 ms | 0.67 cores | kafka 37% · mongo 96% | 0.40 of 1 | mongo 0.96 | 0.96 | 98.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| e2-head | head | 2096.0/s | 2096.0/s | 83840 | 0 | 102.9 ms | 342.0 ms | 481.3 ms | n/a | 618.5 ms | 0.70 cores | kafka 40% · mongo 97% | 0.42 of 1 | mongo 0.97 | 0.99 | 97.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 20 s | - |
| e2-base | base | 2096.0/s | 2096.0/s | 83840 | 0 | 99.8 ms | 311.3 ms | 395.3 ms | n/a | 512.0 ms | 0.67 cores | kafka 39% · mongo 97% | 0.40 of 1 | mongo 0.97 | 0.97 | 97.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 16 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.889 | 0.348 | 0.489 | 0.557 | - | 0.544 | 1.107 | 1.054 |
| 2 | 0.892 | 0.304 | 0.344 | 0.357 | - | 0.329 | 1.101 | 1.054 |
| 3 | 0.889 | 0.469 | 0.716 | 0.730 | - | 0.745 | 1.107 | 1.038 |

Pairs at one rate for both arms, 2096.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.858 | 0.909 | 1.022 | - | 1.046 | 1.002 | 1.004 |
| 2 | 1.000 | 1.031 | 1.099 | 1.218 | - | 1.208 | 1.016 | 1.003 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +143.1% (±25.0%) |
| e1-mongo-uniform@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +25.0% (±25.0%) |
| e1-mongo-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +180.9% (±25.0%) |
| e1-mongo-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +26.8% (±25.0%) |
| e1-mongo-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -26.1% (±25.0%) |
| e1-mongo-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +57.7% (±25.0%) |



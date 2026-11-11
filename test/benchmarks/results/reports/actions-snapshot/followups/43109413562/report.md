# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `d781d71441847d8637586771d937b6c5302a7ab0` d781d71441847d8637586771d937b6c5302a7ab0; V10 baseline: `4e20d0c22ddaf2c4d1a63853f360f94e171933a7` 4e20d0c22ddaf2c4d1a63853f360f94e171933a7; harness and load generator from head.

Tier: custom; coverage: e1-mongo-uniform@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +54.2% (±19.9%) |
| e1-mongo-uniform@100k | r1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +35.8% (±19.9%) |
| e1-mongo-uniform@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -23.6% (±19.9%) |
| e1-mongo-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +155.6% (±19.9%) |
| e1-mongo-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +67.9% (±19.9%) |
| e1-mongo-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +31.8% (±19.9%) |
| e1-mongo-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +88.3% (±19.9%) |
| e1-mongo-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +42.1% (±19.9%) |
| e1-mongo-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +44.8% (±19.9%) |

## Significance

Beyond noise: 1 of 3 ratios; 1 of 1 suites have an A/A noise threshold (test/benchmarks/noise.json). A ratio counts when its 95% bootstrap interval (over the measured seconds) clears the suite's threshold; without one it is `uncalibrated`.

| Suite | b/a | Metric | Rounds | Ratio [95% CI] | Noise | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | throughput | calibrated | 0.874 [0.872–0.878] | ±2.0% | **lower** |
| e1-mongo-uniform@100k | head/base | p50 | equal | 0.597 [0.424–0.919] | ±19.9% | within noise |
| e1-mongo-uniform@100k | head/base | p99 | equal | 0.666 [0.631–1.012] | ±12.1% | within noise |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | base | 2989.1/s | 1024 | 2391.3/s | 80% |
| e1-mongo-uniform@100k | head | 2606.5/s | 512 | 2085.2/s | 80% |

## Paired ratios

`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.

| Suite | b/a | Rounds | Saturation | Throughput | p50 | p95 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | calibrated (unequal load) | 0.872 | 0.875 (0.872–0.876, n=3) | 0.304 (0.135–0.328, n=3) | 0.442 (0.283–0.608, n=3) | 0.490 (0.315–0.796, n=3) | - | 0.586 (0.360–0.838, n=3) | 1.127 (1.112–1.152, n=3) | 1.063 (1.052–1.092, n=3) |
| e1-mongo-uniform@100k | head/base | equal rate 2085.2/s | 0.872 | 1.000 (1.000–1.000, n=2) | 0.774 (0.464–1.085, n=2) | 0.774 (0.535–1.014, n=2) | 0.776 (0.539–1.013, n=2) | - | 0.796 (0.527–1.064, n=2) | 1.021 (0.985–1.057, n=2) | 1.013 (0.996–1.030, n=2) |

---

### e1-mongo-uniform@100k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (33–41 s), then waited until the nodes were quiet again (3–4 s).

- `head`: wow.access.walk-rate=1, wow.access.walk-batch=1

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2989.1/s | 1024 | 2391.3/s | 80% |
| head | 2606.5/s | 512 | 2085.2/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 2391.3/s | 2391.3/s | 47826 | 0 | 99.3 ms | 374.8 ms | 528.4 ms | n/a | 630.8 ms | 0.69 cores | kafka 37% · mongo 95% | 0.50 of 1 | mongo 0.95 | 0.85 | 97.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| probe1-head | head | 2085.2/s | 2085.2/s | 41703 | 0 | 44.8 ms | 299.0 ms | 448.5 ms | n/a | 565.2 ms | 0.67 cores | kafka 39% · mongo 92% | 0.44 of 1 | mongo 0.92 | 0.95 | 100.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 2391.3/s | 2381.3/s | 95254 | 398 | 340.0 ms | 786.4 ms | 1019.9 ms | n/a | 1335.3 ms | 0.62 cores | kafka 34% · mongo 99% | 0.44 of 1 | mongo 0.99 | 0.82 | 92.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 36 s | generator-overload 398 |
| r1-head | head | 2085.2/s | 2085.2/s | 83408 | 0 | 45.8 ms | 222.2 ms | 321.5 ms | n/a | 481.3 ms | 0.66 cores | kafka 36% · mongo 95% | 0.40 of 1 | mongo 0.95 | 0.95 | 101.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| r2-head | head | 2085.2/s | 2085.2/s | 83408 | 0 | 44.3 ms | 218.1 ms | 313.3 ms | n/a | 444.4 ms | 0.64 cores | kafka 37% · mongo 94% | 0.40 of 1 | mongo 0.94 | 0.93 | 100.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| r2-base | base | 2391.3/s | 2391.3/s | 95652 | 0 | 135.2 ms | 493.6 ms | 639.0 ms | n/a | 757.8 ms | 0.67 cores | kafka 36% · mongo 95% | 0.43 of 1 | mongo 0.95 | 0.83 | 94.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 21 s | - |
| r3-base | base | 2391.3/s | 2381.8/s | 95273 | 379 | 315.4 ms | 737.3 ms | 925.7 ms | n/a | 1114.1 ms | 0.71 cores | kafka 42% · mongo 97% | 0.48 of 1 | mongo 0.97 | 0.88 | 92.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 26 s | generator-overload 379 |
| r3-head | head | 2085.2/s | 2085.2/s | 83408 | 0 | 95.7 ms | 448.5 ms | 737.3 ms | n/a | 933.9 ms | 0.69 cores | kafka 40% · mongo 95% | 0.41 of 1 | mongo 0.95 | 0.98 | 96.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | - |
| e1-base | base | 2085.2/s | 2085.2/s | 83408 | 0 | 199.7 ms | 651.3 ms | 835.6 ms | n/a | 1056.8 ms | 0.69 cores | kafka 42% · mongo 98% | 0.42 of 1 | mongo 0.98 | 1.00 | 94.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 18 s | - |
| e1-head | head | 2085.2/s | 2085.2/s | 83408 | 0 | 92.7 ms | 348.2 ms | 450.6 ms | n/a | 557.1 ms | 0.68 cores | kafka 41% · mongo 96% | 0.41 of 1 | mongo 0.96 | 0.98 | 97.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | - |
| e2-head | head | 2085.2/s | 2085.2/s | 83408 | 0 | 59.1 ms | 221.2 ms | 309.2 ms | n/a | 544.8 ms | 0.70 cores | kafka 38% · mongo 96% | 0.41 of 1 | mongo 0.96 | 0.98 | 99.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 12 s | - |
| e2-base | base | 2085.2/s | 2085.2/s | 83408 | 0 | 54.5 ms | 218.1 ms | 305.2 ms | n/a | 512.0 ms | 0.62 cores | kafka 36% · mongo 95% | 0.38 of 1 | mongo 0.95 | 0.93 | 99.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.876 | 0.135 | 0.283 | 0.315 | - | 0.360 | 1.152 | 1.092 |
| 2 | 0.872 | 0.328 | 0.442 | 0.490 | - | 0.586 | 1.127 | 1.063 |
| 3 | 0.875 | 0.304 | 0.608 | 0.796 | - | 0.838 | 1.112 | 1.052 |

Pairs at one rate for both arms, 2085.2/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.464 | 0.535 | 0.539 | - | 0.527 | 0.985 | 1.030 |
| 2 | 1.000 | 1.085 | 1.014 | 1.013 | - | 1.064 | 1.057 | 0.996 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +54.2% (±25.0%) |
| e1-mongo-uniform@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +35.8% (±25.0%) |
| e1-mongo-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +155.6% (±25.0%) |
| e1-mongo-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +67.9% (±25.0%) |
| e1-mongo-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +31.8% (±25.0%) |
| e1-mongo-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +88.3% (±25.0%) |
| e1-mongo-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +42.1% (±25.0%) |
| e1-mongo-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +44.8% (±25.0%) |



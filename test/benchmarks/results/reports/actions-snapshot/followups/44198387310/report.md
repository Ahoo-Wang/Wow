# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `v10.0.0-beta.6` e3a4e937aa8852b24022c25f386fd5c3263ba9ab; V10 baseline: none; harness and load generator from head.

Tier: custom; coverage: e1-mongo-uniform@1k, e3-mongo@1k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@1k | r1-b | steady | **unstable** | second half against first, beyond the default limits: p50 -25.1% (±25.0%) |
| e1-mongo-uniform@1k | r2-b | steady | **unstable** | second half against first, beyond the default limits: p50 -59.9% (±25.0%) |
| e1-mongo-uniform@1k | e1-b | steady | **unstable** | second half against first, beyond the default limits: p50 -36.2% (±25.0%) |
| e3-mongo@1k | r1-a | steady | **p99 > 10× p50** | p50 5.5 ms, p99 97.3 ms |
| e3-mongo@1k | r1-b | steady | **p99 > 10× p50** | p50 7.1 ms, p99 145.4 ms |
| e3-mongo@1k | r2-b | steady | **p99 > 10× p50** | p50 7.4 ms, p99 170.0 ms |
| e3-mongo@1k | r2-a | steady | **p99 > 10× p50** | p50 5.5 ms, p99 113.2 ms |
| e3-mongo@1k | e1-a | steady | **p99 > 10× p50** | p50 6.1 ms, p99 108.5 ms |
| e3-mongo@1k | e1-b | steady | **p99 > 10× p50** | p50 5.5 ms, p99 171.0 ms |
| e3-mongo@1k | e2-b | steady | **p99 > 10× p50** | p50 6.3 ms, p99 120.3 ms |
| e3-mongo@1k | e2-a | steady | **p99 > 10× p50** | p50 6.0 ms, p99 120.8 ms |
| e3-mongo@1k | e3-a | steady | **p99 > 10× p50** | p50 5.7 ms, p99 127.0 ms |
| e3-mongo@1k | e3-b | steady | **p99 > 10× p50** | p50 5.8 ms, p99 118.8 ms |
| e3-mongo@1k | e4-b | steady | **p99 > 10× p50** | p50 5.6 ms, p99 120.8 ms |
| e3-mongo@1k | e4-a | steady | **p99 > 10× p50** | p50 5.8 ms, p99 119.8 ms |

## A/A noise

The head against itself at one rate, 4 rounds or more: each metric's threshold is the half-width of the 95% bootstrap interval of its ratio, at least 2%. The report writes these as noise.json; commit it as test/benchmarks/noise.json to make them the thresholds of later runs.

| Suite | throughput | p50 | p99 |
| --- | --- | --- | --- |
| e1-mongo-uniform@1k | ±2.0% | ±30.0% | ±12.5% |
| e3-mongo@1k | ±2.0% | ±11.6% | ±9.5% |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout | JDBC pool |
| --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@1k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 | - |
| e3-mongo@1k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 | - |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@1k | a | ≥ 2327.4/s | 2048 | 785.5/s | 34% (**not sustained**) |
| e1-mongo-uniform@1k | b | ≥ 2292.9/s | 2048 | 773.8/s | 34% (**not sustained**) |
| e3-mongo@1k | a | 1256.0/s | 256 | 423.9/s | 34% (**not sustained**) |
| e3-mongo@1k | b | 1407.0/s | 512 | 474.9/s | 34% (**not sustained**) |

## Paired ratios

`calibrated`: each arm at its own calibrated rate, so the throughput ratio is the ratio of the calibrations (an open loop's throughput is its offered rate) and CPU and latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency and cost ratios from these rows.

| Suite | b/a | Rounds | Saturation | Throughput | p50 | p95 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@1k | b/a | calibrated (unequal load) | ~0.985 | 0.985 (0.985–0.985, n=2) | 1.001 (0.844–1.157, n=2) | 0.989 (0.835–1.144, n=2) | 0.997 (0.803–1.192, n=2) | - | 0.945 (0.574–1.316, n=2) | 1.048 (0.998–1.097, n=2) | 1.009 (0.985–1.032, n=2) |
| e1-mongo-uniform@1k | b/a | equal rate 773.8/s | ~0.985 | 1.000 (1.000–1.000, n=4) | 1.040 (0.929–1.074, n=4) | 1.097 (0.945–1.255, n=4) | 1.103 (0.896–1.325, n=4) | - | 1.250 (0.849–1.426, n=4) | 1.000 (0.950–1.015, n=4) | 0.989 (0.982–1.015, n=4) |
| e3-mongo@1k | b/a | calibrated (unequal load) | 1.120 | 1.120 (1.120–1.120, n=2) | 1.306 (1.283–1.330, n=2) | 1.320 (1.313–1.327, n=2) | 1.499 (1.495–1.502, n=2) | - | 1.118 (1.068–1.169, n=2) | 0.907 (0.905–0.910, n=2) | 0.922 (0.919–0.925, n=2) |
| e3-mongo@1k | b/a | equal rate 423.9/s | 1.120 | 1.000 (1.000–1.000, n=4) | 0.995 (0.911–1.043, n=4) | 0.994 (0.975–1.008, n=4) | 1.002 (0.935–1.575, n=4) | - | 0.957 (0.772–1.607, n=4) | 1.004 (0.999–1.011, n=4) | 0.999 (0.992–1.021, n=4) |

---

### e1-mongo-uniform@1k (A/A: head against head)

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput as the closed-loop saturation of each arm, latency and CPU at one rate.

E1, uniform keys over 1000 accounts; warm-up 120 s, measured 40 s, 80% of saturation, 2 round(s). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

- `a`: build e3a4e937aa
- `b`: build e3a4e937aa

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| a | ≥ 2327.4/s | 2048 | 785.5/s | 34% (**not sustained**) |
| b | ≥ 2292.9/s | 2048 | 773.8/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-a | a | 1861.9/s | 829.1/s | 24873 | 30983 | 2293.8 ms | 3801.1 ms | 4620.3 ms | n/a | 5308.4 ms | 0.99 cores | kafka 61% · mongo 56% | 0.48 of 1 | server 0.99 | 2.61 | 35.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 16 s | generator-overload 30979 · http-504-wow.wait.timeout 4 |
| probe2-a | a | 1396.4/s | 809.4/s | 24282 | 17609 | 2342.9 ms | 3981.3 ms | 4718.6 ms | n/a | 5537.8 ms | 0.99 cores | kafka 61% · mongo 56% | 0.47 of 1 | server 0.99 | 2.67 | 45.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | generator-overload 17609 |
| probe3-a | a | 1047.3/s | 795.4/s | 23862 | 7557 | 2359.3 ms | 3981.3 ms | 4718.6 ms | n/a | 5505.0 ms | 0.99 cores | kafka 59% · mongo 57% | 0.43 of 1 | server 0.99 | 2.71 | 60.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 19 s | generator-overload 7557 |
| probe4-a | a | 785.5/s | 739.7/s | 22192 | 1373 | 1941.5 ms | 3932.2 ms | 4849.7 ms | n/a | 5931.0 ms | 1.00 cores | kafka 58% · mongo 58% | 0.41 of 1 | server 1.00 | 2.91 | 78.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | generator-overload 1373 |
| probe1-b | b | 1834.3/s | 861.9/s | 25858 | 29171 | 2179.1 ms | 3620.9 ms | 4259.8 ms | n/a | 5308.4 ms | 0.99 cores | kafka 59% · mongo 58% | 0.47 of 1 | server 0.99 | 2.52 | 36.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | generator-overload 29171 |
| probe2-b | b | 1375.7/s | 818.7/s | 24560 | 16711 | 2326.5 ms | 3784.7 ms | 4456.4 ms | n/a | 5275.6 ms | 1.00 cores | kafka 60% · mongo 58% | 0.44 of 1 | server 1.00 | 2.66 | 46.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | generator-overload 16711 |
| probe3-b | b | 1031.8/s | 730.9/s | 21927 | 9027 | 2506.8 ms | 4292.6 ms | 5013.5 ms | n/a | 5767.2 ms | 0.99 cores | kafka 62% · mongo 55% | 0.48 of 1 | server 0.99 | 2.97 | 56.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 18 s | generator-overload 9027 |
| probe4-b | b | 773.8/s | 649.6/s | 19488 | 3726 | 2719.7 ms | 4620.3 ms | 5537.8 ms | n/a | 6881.3 ms | 1.00 cores | kafka 63% · mongo 55% | 0.45 of 1 | server 1.00 | 3.35 | 68.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 16 s | generator-overload 3725 · http-504-wow.wait.timeout 1 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-a | a | 785.5/s | 785.5/s | 31420 | 0 | 22.1 ms | 69.6 ms | 106.5 ms | n/a | 208.9 ms | 0.98 cores | kafka 52% · mongo 90% | 0.30 of 1 | server 0.98 | 3.05 | 90.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| r1-b | b | 773.8/s | 773.8/s | 30952 | 0 | 18.7 ms | 58.1 ms | 85.5 ms | n/a | 119.8 ms | 0.94 cores | kafka 51% · mongo 91% | 0.28 of 1 | server 0.94 | 3.05 | 93.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r2-b | b | 773.8/s | 773.8/s | 30952 | 0 | 14.6 ms | 54.8 ms | 89.1 ms | n/a | 153.6 ms | 0.85 cores | kafka 46% · mongo 90% | 0.29 of 1 | mongo 0.90 | 2.87 | 98.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r2-a | a | 785.5/s | 785.5/s | 31420 | 0 | 12.6 ms | 47.9 ms | 74.8 ms | n/a | 116.7 ms | 0.67 cores | kafka 46% · mongo 92% | 0.28 of 1 | mongo 0.92 | 2.62 | 99.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e1-a | a | 773.8/s | 773.8/s | 30952 | 0 | 13.5 ms | 50.4 ms | 83.5 ms | n/a | 132.1 ms | 0.71 cores | kafka 49% · mongo 92% | 0.30 of 1 | mongo 0.92 | 2.74 | 98.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-b | b | 773.8/s | 773.8/s | 30952 | 0 | 14.1 ms | 50.7 ms | 74.8 ms | n/a | 112.1 ms | 0.79 cores | kafka 45% · mongo 91% | 0.29 of 1 | mongo 0.91 | 2.78 | 97.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-b | b | 773.8/s | 773.8/s | 30952 | 0 | 14.2 ms | 52.7 ms | 86.5 ms | n/a | 150.5 ms | 0.73 cores | kafka 46% · mongo 92% | 0.28 of 1 | mongo 0.92 | 2.73 | 97.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-a | a | 773.8/s | 773.8/s | 30952 | 0 | 15.3 ms | 55.8 ms | 88.6 ms | n/a | 126.5 ms | 0.80 cores | kafka 52% · mongo 91% | 0.30 of 1 | mongo 0.91 | 2.88 | 96.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |
| e3-a | a | 773.8/s | 773.8/s | 30952 | 0 | 17.4 ms | 54.3 ms | 80.4 ms | n/a | 121.3 ms | 0.94 cores | kafka 51% · mongo 90% | 0.29 of 1 | server 0.94 | 3.03 | 94.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-b | b | 773.8/s | 773.8/s | 30952 | 0 | 18.7 ms | 64.5 ms | 106.5 ms | n/a | 173.1 ms | 0.95 cores | kafka 49% · mongo 89% | 0.29 of 1 | server 0.95 | 3.02 | 92.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e4-b | b | 773.8/s | 773.8/s | 30952 | 0 | 15.7 ms | 65.5 ms | 104.4 ms | n/a | 173.1 ms | 0.84 cores | kafka 48% · mongo 91% | 0.28 of 1 | mongo 0.91 | 2.88 | 95.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e4-a | a | 773.8/s | 773.8/s | 30952 | 0 | 15.2 ms | 52.2 ms | 85.0 ms | n/a | 132.1 ms | 0.81 cores | kafka 49% · mongo 91% | 0.28 of 1 | mongo 0.91 | 2.86 | 96.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (each arm at its own rate: an open loop's throughput is its offered rate, so the throughput ratio is the calibrations'; CPU and latency are at unequal loads):

| Round | Throughput b/a | p50 b/a | p95 b/a | p99 b/a | p999 b/a | Worst-second p99 b/a | CPU ms/op b/a | Alloc/op b/a |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.985 | 0.844 | 0.835 | 0.803 | - | 0.574 | 0.998 | 1.032 |
| 2 | 0.985 | 1.157 | 1.144 | 1.192 | - | 1.316 | 1.097 | 0.985 |

Pairs at one rate for both arms, 773.8/s (latencies compare; lower is better):

| Round | Throughput b/a | p50 b/a | p95 b/a | p99 b/a | p999 b/a | Worst-second p99 b/a | CPU ms/op b/a | Alloc/op b/a |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.043 | 1.005 | 0.896 | - | 0.849 | 1.015 | 0.994 |
| 2 | 1.000 | 0.929 | 0.945 | 0.977 | - | 1.190 | 0.950 | 1.015 |
| 3 | 1.000 | 1.074 | 1.189 | 1.325 | - | 1.426 | 0.994 | 0.984 |
| 4 | 1.000 | 1.038 | 1.255 | 1.229 | - | 1.310 | 1.006 | 0.982 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@1k | r1-b | steady | **unstable** | second half against first, beyond the default limits: p50 -25.1% (±25.0%) |
| e1-mongo-uniform@1k | r2-b | steady | **unstable** | second half against first, beyond the default limits: p50 -59.9% (±25.0%) |
| e1-mongo-uniform@1k | e1-b | steady | **unstable** | second half against first, beyond the default limits: p50 -36.2% (±25.0%) |


### e3-mongo@1k (A/A: head against head)

E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.

E3, uniform keys over 1000 accounts; warm-up 120 s, measured 40 s, 80% of saturation, 2 round(s). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

- `a`: build e3a4e937aa
- `b`: build e3a4e937aa

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| a | 1256.0/s | 256 | 423.9/s | 34% (**not sustained**) |
| b | 1407.0/s | 512 | 474.9/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-a | a | 1004.8/s | 468.4/s | 14053 | 16091 | 3309.6 ms | 7897.1 ms | 9502.7 ms | n/a | 10289.2 ms | 1.00 cores | kafka 42% · mongo 58% | 0.24 of 1 | server 1.00 | 4.27 | 160.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | generator-overload 15784 · http-504-wow.wait.timeout 307 |
| probe2-a | a | 753.6/s | 529.3/s | 15880 | 6727 | 2588.7 ms | 7438.3 ms | 9306.1 ms | n/a | 19660.8 ms | 1.00 cores | kafka 43% · mongo 52% | 0.26 of 1 | server 1.00 | 3.66 | 199.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | generator-overload 6018 · http-504-wow.wait.timeout 709 |
| probe3-a | a | 565.2/s | 435.0/s | 13050 | 3905 | 3571.7 ms | 8126.5 ms | 9633.8 ms | n/a | 10223.6 ms | 1.00 cores | kafka 42% · mongo 57% | 0.24 of 1 | server 1.00 | 4.56 | 260.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | generator-overload 3530 · http-504-wow.wait.timeout 375 |
| probe4-a | a | 423.9/s | 423.9/s | 12717 | 0 | 70.7 ms | 2883.6 ms | 4292.6 ms | n/a | 6127.6 ms | 1.00 cores | kafka 42% · mongo 65% | 0.23 of 1 | server 1.00 | 4.88 | 262.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| probe1-b | b | 1125.6/s | 428.0/s | 12841 | 20927 | 2916.4 ms | 8355.8 ms | 9830.4 ms | n/a | 10223.6 ms | 1.00 cores | kafka 44% · mongo 57% | 0.27 of 1 | server 1.00 | 4.68 | 127.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | generator-overload 19766 · http-504-wow.wait.timeout 1161 |
| probe2-b | b | 844.2/s | 473.5/s | 14206 | 11119 | 3276.8 ms | 7667.7 ms | 9502.7 ms | n/a | 10092.5 ms | 1.00 cores | kafka 42% · mongo 60% | 0.24 of 1 | server 1.00 | 4.25 | 194.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | generator-overload 10720 · http-504-wow.wait.timeout 399 |
| probe3-b | b | 633.2/s | 500.6/s | 15017 | 3979 | 3162.1 ms | 7569.4 ms | 9437.2 ms | n/a | 10027.0 ms | 1.00 cores | kafka 42% · mongo 60% | 0.25 of 1 | server 1.00 | 4.02 | 272.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | generator-overload 3703 · http-504-wow.wait.timeout 276 |
| probe4-b | b | 474.9/s | 459.4/s | 13783 | 464 | 2375.7 ms | 6553.6 ms | 8585.2 ms | n/a | 10092.5 ms | 1.00 cores | kafka 42% · mongo 59% | 0.21 of 1 | server 1.00 | 4.36 | 305.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | generator-overload 384 · http-504-wow.wait.timeout 80 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-a | a | 423.9/s | 423.9/s | 16956 | 0 | 5.5 ms | 56.3 ms | 97.3 ms | n/a | 236.5 ms | 0.49 cores | kafka 34% · mongo 85% | 0.11 of 1 | mongo 0.85 | 3.95 | 298.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r1-b | b | 474.9/s | 474.9/s | 18996 | 0 | 7.1 ms | 74.8 ms | 145.4 ms | n/a | 276.5 ms | 0.51 cores | kafka 34% · mongo 86% | 0.12 of 1 | mongo 0.86 | 3.59 | 273.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r2-b | b | 474.9/s | 474.9/s | 18996 | 0 | 7.4 ms | 77.3 ms | 170.0 ms | n/a | 323.6 ms | 0.52 cores | kafka 34% · mongo 85% | 0.12 of 1 | mongo 0.85 | 3.60 | 273.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r2-a | a | 423.9/s | 423.9/s | 16956 | 0 | 5.5 ms | 58.9 ms | 113.2 ms | n/a | 303.1 ms | 0.49 cores | kafka 34% · mongo 85% | 0.11 of 1 | mongo 0.85 | 3.98 | 295.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-a | a | 423.9/s | 423.9/s | 16956 | 0 | 6.1 ms | 64.5 ms | 108.5 ms | n/a | 219.1 ms | 0.50 cores | kafka 34% · mongo 85% | 0.12 of 1 | mongo 0.85 | 3.98 | 290.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-b | b | 423.9/s | 423.9/s | 16956 | 0 | 5.5 ms | 64.0 ms | 171.0 ms | n/a | 352.3 ms | 0.49 cores | kafka 34% · mongo 86% | 0.11 of 1 | mongo 0.86 | 3.98 | 296.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-b | b | 423.9/s | 423.9/s | 16956 | 0 | 6.3 ms | 60.2 ms | 120.3 ms | n/a | 235.5 ms | 0.51 cores | kafka 37% · mongo 84% | 0.11 of 1 | mongo 0.84 | 4.06 | 289.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-a | a | 423.9/s | 423.9/s | 16956 | 0 | 6.0 ms | 60.4 ms | 120.8 ms | n/a | 305.2 ms | 0.51 cores | kafka 36% · mongo 85% | 0.11 of 1 | mongo 0.85 | 4.04 | 290.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-a | a | 423.9/s | 423.9/s | 16956 | 0 | 5.7 ms | 67.6 ms | 127.0 ms | n/a | 276.5 ms | 0.49 cores | kafka 35% · mongo 84% | 0.11 of 1 | mongo 0.84 | 3.97 | 291.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e3-b | b | 423.9/s | 423.9/s | 16956 | 0 | 5.8 ms | 68.1 ms | 118.8 ms | n/a | 245.8 ms | 0.49 cores | kafka 35% · mongo 85% | 0.11 of 1 | mongo 0.85 | 3.98 | 292.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e4-b | b | 423.9/s | 423.9/s | 16956 | 0 | 5.6 ms | 59.6 ms | 120.8 ms | n/a | 243.7 ms | 0.50 cores | kafka 33% · mongo 85% | 0.11 of 1 | mongo 0.85 | 3.94 | 293.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e4-a | a | 423.9/s | 423.9/s | 16956 | 0 | 5.8 ms | 61.2 ms | 119.8 ms | n/a | 237.6 ms | 0.49 cores | kafka 33% · mongo 84% | 0.11 of 1 | mongo 0.84 | 3.90 | 295.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (each arm at its own rate: an open loop's throughput is its offered rate, so the throughput ratio is the calibrations'; CPU and latency are at unequal loads):

| Round | Throughput b/a | p50 b/a | p95 b/a | p99 b/a | p999 b/a | Worst-second p99 b/a | CPU ms/op b/a | Alloc/op b/a |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.120 | 1.283 | 1.327 | 1.495 | - | 1.169 | 0.910 | 0.919 |
| 2 | 1.120 | 1.330 | 1.313 | 1.502 | - | 1.068 | 0.905 | 0.925 |

Pairs at one rate for both arms, 423.9/s (latencies compare; lower is better):

| Round | Throughput b/a | p50 b/a | p95 b/a | p99 b/a | p999 b/a | Worst-second p99 b/a | CPU ms/op b/a | Alloc/op b/a |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.911 | 0.992 | 1.575 | - | 1.607 | 0.999 | 1.021 |
| 2 | 1.000 | 1.043 | 0.996 | 0.996 | - | 0.772 | 1.003 | 0.995 |
| 3 | 1.000 | 1.022 | 1.008 | 0.935 | - | 0.889 | 1.005 | 1.002 |
| 4 | 1.000 | 0.967 | 0.975 | 1.009 | - | 1.026 | 1.011 | 0.992 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@1k | r1-a | steady | **p99 > 10× p50** | p50 5.5 ms, p99 97.3 ms |
| e3-mongo@1k | r1-b | steady | **p99 > 10× p50** | p50 7.1 ms, p99 145.4 ms |
| e3-mongo@1k | r2-b | steady | **p99 > 10× p50** | p50 7.4 ms, p99 170.0 ms |
| e3-mongo@1k | r2-a | steady | **p99 > 10× p50** | p50 5.5 ms, p99 113.2 ms |
| e3-mongo@1k | e1-a | steady | **p99 > 10× p50** | p50 6.1 ms, p99 108.5 ms |
| e3-mongo@1k | e1-b | steady | **p99 > 10× p50** | p50 5.5 ms, p99 171.0 ms |
| e3-mongo@1k | e2-b | steady | **p99 > 10× p50** | p50 6.3 ms, p99 120.3 ms |
| e3-mongo@1k | e2-a | steady | **p99 > 10× p50** | p50 6.0 ms, p99 120.8 ms |
| e3-mongo@1k | e3-a | steady | **p99 > 10× p50** | p50 5.7 ms, p99 127.0 ms |
| e3-mongo@1k | e3-b | steady | **p99 > 10× p50** | p50 5.8 ms, p99 118.8 ms |
| e3-mongo@1k | e4-b | steady | **p99 > 10× p50** | p50 5.6 ms, p99 120.8 ms |
| e3-mongo@1k | e4-a | steady | **p99 > 10× p50** | p50 5.8 ms, p99 119.8 ms |



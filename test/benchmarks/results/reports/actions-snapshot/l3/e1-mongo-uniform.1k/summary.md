## e1-mongo-uniform@1k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2006.1/s | 2048 | 677.1/s | 34% (**not sustained**) |
| head | 2008.5/s | 2048 | 677.9/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1604.9/s | 682.9/s | 13658 | 18440 | 2621.4 ms | 4194.3 ms | 4980.7 ms | n/a | 5865.5 ms | 0.99 cores | kafka 66% · mongo 56% | 0.51 of 1 | server 0.99 | 3.23 | 43.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | generator-overload 18440 |
| probe2-base | base | 1203.7/s | 662.6/s | 13253 | 10821 | 2687.0 ms | 4489.2 ms | 5931.0 ms | n/a | 10551.3 ms | 0.99 cores | kafka 68% · mongo 56% | 0.51 of 1 | server 0.99 | 3.37 | 56.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | generator-overload 10784 · http-504-wow.wait.timeout 37 |
| probe3-base | base | 902.8/s | 694.1/s | 13882 | 4174 | 2555.9 ms | 3932.2 ms | 4653.1 ms | n/a | 5505.0 ms | 1.00 cores | kafka 66% · mongo 54% | 0.53 of 1 | server 1.00 | 3.17 | 78.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | generator-overload 4174 |
| probe4-base | base | 677.1/s | 653.2/s | 13064 | 478 | 2277.4 ms | 3866.6 ms | 4620.3 ms | n/a | 5308.4 ms | 0.99 cores | kafka 65% · mongo 56% | 0.46 of 1 | server 0.99 | 3.38 | 104.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | generator-overload 478 |
| probe1-head | head | 1606.8/s | 692.8/s | 13856 | 18280 | 2687.0 ms | 4227.1 ms | 5013.5 ms | n/a | 5668.9 ms | 1.00 cores | kafka 68% · mongo 56% | 0.58 of 1 | server 1.00 | 3.22 | 44.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | generator-overload 18280 |
| probe2-head | head | 1205.1/s | 605.4/s | 12108 | 11994 | 3031.0 ms | 4849.7 ms | 5701.6 ms | n/a | 6684.7 ms | 0.99 cores | kafka 69% · mongo 50% | 0.48 of 1 | server 0.99 | 3.61 | 51.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | generator-overload 11994 |
| probe3-head | head | 903.8/s | 590.5/s | 11809 | 6267 | 2768.9 ms | 5079.0 ms | 8519.7 ms | n/a | 10616.8 ms | 0.99 cores | kafka 66% · mongo 55% | 0.47 of 1 | server 0.99 | 3.73 | 70.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | generator-overload 6125 · http-504-wow.wait.timeout 142 |
| probe4-head | head | 677.9/s | 621.7/s | 12434 | 1124 | 2670.6 ms | 4177.9 ms | 5046.3 ms | n/a | 6062.1 ms | 1.00 cores | kafka 66% · mongo 56% | 0.41 of 1 | server 1.00 | 3.56 | 98.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | generator-overload 1124 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 677.1/s | 677.1/s | 27084 | 0 | 142.3 ms | 958.5 ms | 1417.2 ms | n/a | 1990.7 ms | 1.00 cores | kafka 52% · mongo 75% | 0.27 of 1 | server 1.00 | 3.35 | 113.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |
| r1-head | head | 677.9/s | 677.9/s | 27116 | 0 | 76.8 ms | 1441.8 ms | 2080.8 ms | n/a | 3080.2 ms | 0.99 cores | kafka 50% · mongo 77% | 0.26 of 1 | server 0.99 | 3.35 | 121.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| r2-head | head | 677.9/s | 677.9/s | 27116 | 0 | 229.4 ms | 1900.5 ms | 2555.9 ms | n/a | 3653.6 ms | 1.00 cores | kafka 50% · mongo 72% | 0.27 of 1 | server 1.00 | 3.27 | 114.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| r2-base | base | 677.1/s | 677.1/s | 27084 | 0 | 140.3 ms | 1728.5 ms | 2326.5 ms | n/a | 3129.3 ms | 0.99 cores | kafka 50% · mongo 74% | 0.27 of 1 | server 0.99 | 3.30 | 115.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r3-base | base | 677.1/s | 677.1/s | 27084 | 0 | 331.8 ms | 1671.2 ms | 2211.8 ms | n/a | 2900.0 ms | 1.00 cores | kafka 51% · mongo 70% | 0.28 of 1 | server 1.00 | 3.26 | 112.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| r3-head | head | 677.9/s | 677.9/s | 27116 | 0 | 247.8 ms | 2031.6 ms | 2621.4 ms | n/a | 3768.3 ms | 1.00 cores | kafka 53% · mongo 70% | 0.27 of 1 | server 1.00 | 3.29 | 112.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| e1-base | base | 677.1/s | 668.4/s | 26737 | 347 | 385.0 ms | 2523.1 ms | 5603.3 ms | n/a | 9895.9 ms | 1.00 cores | kafka 52% · mongo 68% | 0.28 of 1 | server 1.00 | 3.28 | 111.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | http-504-wow.wait.timeout 347 |
| e1-head | head | 677.1/s | 677.1/s | 27084 | 0 | 228.4 ms | 2441.2 ms | 3145.7 ms | n/a | 4390.9 ms | 1.00 cores | kafka 49% · mongo 70% | 0.26 of 1 | server 1.00 | 3.23 | 114.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | - |
| e2-head | head | 677.1/s | 677.1/s | 27084 | 0 | 313.3 ms | 2392.1 ms | 3145.7 ms | n/a | 4423.7 ms | 1.00 cores | kafka 54% · mongo 69% | 0.27 of 1 | server 1.00 | 3.29 | 114.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| e2-base | base | 677.1/s | 677.1/s | 27084 | 0 | 140.3 ms | 1671.2 ms | 2261.0 ms | n/a | 3063.8 ms | 0.99 cores | kafka 49% · mongo 76% | 0.26 of 1 | server 0.99 | 3.31 | 118.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.001 | 0.540 | 1.504 | 1.468 | - | 1.547 | 1.001 | 1.064 |
| 2 | 1.001 | 1.635 | 1.100 | 1.099 | - | 1.168 | 0.990 | 0.991 |
| 3 | 1.001 | 0.747 | 1.216 | 1.185 | - | 1.299 | 1.010 | 0.995 |

Pairs at one rate for both arms, 677.1/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.013 | 0.593 | 0.968 | 0.561 | - | 0.444 | 0.984 | 1.031 |
| 2 | 1.000 | 2.234 | 1.431 | 1.391 | - | 1.444 | 0.994 | 0.963 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -58.4% (±25.0%) |
| e1-mongo-uniform@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -79.6% (±25.0%) |
| e1-mongo-uniform@1k | r1-head | steady | **p99 > 10× p50** | p50 76.8 ms, p99 2080.8 ms |
| e1-mongo-uniform@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -84.4% (±25.0%) |
| e1-mongo-uniform@1k | r2-head | steady | **p99 > 10× p50** | p50 229.4 ms, p99 2555.9 ms |
| e1-mongo-uniform@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -91.2% (±25.0%) |
| e1-mongo-uniform@1k | r2-base | steady | **p99 > 10× p50** | p50 140.3 ms, p99 2326.5 ms |
| e1-mongo-uniform@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -77.1% (±25.0%) |
| e1-mongo-uniform@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -83.4% (±25.0%) |
| e1-mongo-uniform@1k | r3-head | steady | **p99 > 10× p50** | p50 247.8 ms, p99 2621.4 ms |
| e1-mongo-uniform@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -75.5% (±25.0%) |
| e1-mongo-uniform@1k | e1-base | steady | **error spike** | 347 of 27084 failed: http-504-wow.wait.timeout 347 |
| e1-mongo-uniform@1k | e1-base | steady | **p99 > 10× p50** | p50 385.0 ms, p99 5603.3 ms |
| e1-mongo-uniform@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -88.2% (±25.0%) |
| e1-mongo-uniform@1k | e1-head | steady | **p99 > 10× p50** | p50 228.4 ms, p99 3145.7 ms |
| e1-mongo-uniform@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -87.9% (±25.0%) |
| e1-mongo-uniform@1k | e2-head | steady | **p99 > 10× p50** | p50 313.3 ms, p99 3145.7 ms |
| e1-mongo-uniform@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -93.1% (±25.0%) |
| e1-mongo-uniform@1k | e2-base | steady | **p99 > 10× p50** | p50 140.3 ms, p99 2261.0 ms |


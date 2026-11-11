# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `bc9afce234f6fc89d4fb4caffbf367a67337138f` bc9afce234f6fc89d4fb4caffbf367a67337138f; V10 baseline: `20e32fb9e8863e52311252c1ef7229c5900e9749` 20e32fb9e8863e52311252c1ef7229c5900e9749; harness and load generator from head.

Tier: custom; coverage: e1-pg-uniform@100k, e3-pg@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-pg-uniform@100k | r1-head | steady | **p99 > 10× p50** | p50 42.0 ms, p99 671.7 ms |
| e1-pg-uniform@100k | r2-head | steady | **p99 > 10× p50** | p50 58.4 ms, p99 716.8 ms |
| e1-pg-uniform@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +60.1% (±46.2%) |
| e1-pg-uniform@100k | r4-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +314.4% (±46.2%) |
| e1-pg-uniform@100k | r5-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +49.0% (±46.2%) |
| e1-pg-uniform@100k | r5-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +157.8% (±46.2%) |
| e1-pg-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +105.1% (±46.2%) |
| e1-pg-uniform@100k | e1-base | steady | **p99 > 10× p50** | p50 51.7 ms, p99 880.6 ms |
| e1-pg-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -58.1% (±46.2%) |
| e1-pg-uniform@100k | e1-head | steady | **p99 > 10× p50** | p50 53.2 ms, p99 581.6 ms |
| e1-pg-uniform@100k | e2-head | steady | **p99 > 10× p50** | p50 47.6 ms, p99 1073.2 ms |
| e1-pg-uniform@100k | e2-base | steady | **p99 > 10× p50** | p50 54.8 ms, p99 782.3 ms |
| e1-pg-uniform@100k | e3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -51.9% (±46.2%) |
| e1-pg-uniform@100k | e4-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +81.1% (±46.2%) |
| e1-pg-uniform@100k | e4-head | steady | **p99 > 10× p50** | p50 58.4 ms, p99 622.6 ms |
| e1-pg-uniform@100k | e4-base | steady | **p99 > 10× p50** | p50 31.4 ms, p99 344.1 ms |
| e3-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -58.0% (±37.3%) |
| e3-pg@100k | r1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -38.7% (±37.3%) |
| e3-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -69.8% (±37.3%) |
| e3-pg@100k | r2-head | steady | **p99 > 10× p50** | p50 51.5 ms, p99 696.3 ms |
| e3-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -60.0% (±37.3%) |
| e3-pg@100k | r2-base | steady | **p99 > 10× p50** | p50 40.4 ms, p99 655.4 ms |
| e3-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +108.1% (±37.3%) |
| e3-pg@100k | r4-base | steady | **p99 > 10× p50** | p50 37.4 ms, p99 544.8 ms |
| e3-pg@100k | r5-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -64.4% (±37.3%) |
| e3-pg@100k | r5-base | steady | **p99 > 10× p50** | p50 36.6 ms, p99 421.9 ms |
| e3-pg@100k | r5-head | steady | **p99 > 10× p50** | p50 36.4 ms, p99 415.7 ms |
| e3-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 36.1 ms, p99 671.7 ms |
| e3-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 35.8 ms, p99 565.2 ms |
| e3-pg@100k | e3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -57.0% (±37.3%) |
| e3-pg@100k | e4-head | steady | **p99 > 10× p50** | p50 53.0 ms, p99 585.7 ms |

## Significance

Beyond noise: 1 of 6 ratios; 2 of 2 suites have an A/A noise threshold (test/benchmarks/noise.json). A ratio counts when its 95% bootstrap interval (over the measured seconds) clears the suite's threshold; without one it is `uncalibrated`.

| Suite | b/a | Metric | Rounds | Ratio [95% CI] | Noise | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| e1-pg-uniform@100k | head/base | throughput | calibrated | 0.828 [0.828–0.829] | ±2.0% | **lower** |
| e1-pg-uniform@100k | head/base | p50 | equal | 0.947 [0.417–2.014] | ±46.2% | within noise |
| e1-pg-uniform@100k | head/base | p99 | equal | 0.880 [0.755–1.347] | ±22.7% | within noise |
| e3-pg@100k | head/base | throughput | calibrated | 1.013 [1.013–1.014] | ±2.0% | within noise |
| e3-pg@100k | head/base | p50 | equal | 1.041 [0.656–1.405] | ±37.3% | within noise |
| e3-pg@100k | head/base | p99 | equal | 1.370 [0.728–1.406] | ±39.5% | within noise |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| e1-pg-uniform@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 2-3 · node 0-1 |
| e3-pg@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 2-3 · node 0-1 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |
| e1-pg-uniform@100k | base | 2108.4/s | 2048 | 1686.7/s | 80% |
| e1-pg-uniform@100k | head | 2327.8/s | 2048 | 1396.7/s | 60% |
| e3-pg@100k | base | 555.8/s | 64 | 444.6/s | 80% |
| e3-pg@100k | head | 751.0/s | 1024 | 450.6/s | 60% |

## Paired ratios

`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.

| Suite | b/a | Rounds | Saturation | Throughput | p50 | p95 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-pg-uniform@100k | head/base | calibrated (unequal load) | 1.104 | 0.828 (0.828–0.830, n=5) | 0.187 (0.143–0.236, n=5) | 0.372 (0.324–0.562, n=5) | 0.459 (0.394–0.698, n=5) | - | 0.461 (0.332–0.811, n=5) | 1.197 (1.191–1.205, n=5) | 1.029 (1.023–1.047, n=5) |
| e1-pg-uniform@100k | head/base | equal rate 1396.7/s | 1.104 | 1.000 (1.000–1.000, n=4) | 0.949 (0.798–1.861, n=4) | 0.989 (0.613–1.857, n=4) | 1.067 (0.660–1.810, n=4) | - | 1.152 (0.651–1.758, n=4) | 1.001 (0.991–1.002, n=4) | 1.005 (0.995–1.021, n=4) |
| e3-pg@100k | head/base | calibrated (unequal load) | 1.351 | 1.013 (1.013–1.013, n=5) | 0.993 (0.868–1.272, n=5) | 0.755 (0.714–1.069, n=5) | 0.981 (0.472–1.063, n=5) | - | 1.037 (0.548–2.131, n=5) | 0.988 (0.985–0.989, n=5) | 1.002 (0.989–1.012, n=5) |
| e3-pg@100k | head/base | equal rate 444.6/s | 1.351 | 1.000 (1.000–1.000, n=4) | 0.959 (0.922–1.424, n=4) | 1.187 (0.934–1.586, n=4) | 0.984 (0.841–1.755, n=4) | - | 0.879 (0.803–1.623, n=4) | 1.001 (0.997–1.003, n=4) | 1.010 (1.003–1.019, n=4) |

---

### e1-pg-uniform@100k

E1 write on PostgreSQL: head against baseline.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 4 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (63–70 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2108.4/s | 2048 | 1686.7/s | 80% |
| head | 2327.8/s | 2048 | 1396.7/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1686.7/s | 1670.8/s | 33417 | 316 | 417.8 ms | 1130.5 ms | 1441.8 ms | n/a | 1630.2 ms | 0.87 cores | kafka 47% · postgres 67% | 0.57 of 2 | server 0.87 | 1.20 | 88.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | generator-overload 316 |
| probe1-head | head | 1862.2/s | 1829.4/s | 36588 | 656 | 598.0 ms | 1204.2 ms | 1417.2 ms | n/a | 1597.4 ms | 0.95 cores | kafka 44% · postgres 63% | 0.62 of 2 | server 0.95 | 1.11 | 89.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 19 s | generator-overload 656 |
| probe2-head | head | 1396.7/s | 1396.7/s | 27933 | 0 | 78.8 ms | 419.8 ms | 520.2 ms | n/a | 610.3 ms | 0.89 cores | kafka 45% · postgres 63% | 0.54 of 2 | server 0.89 | 1.42 | 92.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1686.7/s | 1686.7/s | 67467 | 0 | 292.9 ms | 831.5 ms | 962.6 ms | n/a | 1146.9 ms | 0.89 cores | kafka 48% · postgres 66% | 0.54 of 2 | server 0.89 | 1.20 | 92.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 17 s | - |
| r1-head | head | 1396.7/s | 1396.7/s | 55867 | 0 | 42.0 ms | 466.9 ms | 671.7 ms | n/a | 872.4 ms | 0.88 cores | kafka 48% · postgres 67% | 0.49 of 2 | server 0.88 | 1.45 | 94.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 8 s | - |
| r2-head | head | 1396.7/s | 1396.7/s | 55867 | 0 | 58.4 ms | 278.5 ms | 716.8 ms | n/a | 1036.3 ms | 0.88 cores | kafka 48% · postgres 66% | 0.50 of 2 | server 0.88 | 1.44 | 94.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | - |
| r2-base | base | 1686.7/s | 1686.7/s | 67467 | 0 | 311.3 ms | 860.2 ms | 1065.0 ms | n/a | 1278.0 ms | 0.93 cores | kafka 46% · postgres 64% | 0.53 of 2 | server 0.93 | 1.21 | 91.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 18 s | - |
| r3-base | base | 1686.7/s | 1686.7/s | 67467 | 0 | 286.7 ms | 880.6 ms | 1155.1 ms | n/a | 1400.8 ms | 0.91 cores | kafka 49% · postgres 64% | 0.54 of 2 | server 0.91 | 1.21 | 90.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 18 s | - |
| r3-head | head | 1396.7/s | 1396.7/s | 55867 | 0 | 54.0 ms | 327.7 ms | 454.7 ms | n/a | 585.7 ms | 0.90 cores | kafka 46% · postgres 65% | 0.51 of 2 | server 0.90 | 1.44 | 95.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | - |
| r4-head | head | 1396.7/s | 1396.7/s | 55867 | 0 | 71.7 ms | 407.6 ms | 548.9 ms | n/a | 675.8 ms | 0.90 cores | kafka 49% · postgres 62% | 0.49 of 2 | server 0.90 | 1.45 | 93.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | - |
| r4-base | base | 1686.7/s | 1682.7/s | 67307 | 160 | 303.1 ms | 958.5 ms | 1196.0 ms | n/a | 1466.4 ms | 0.92 cores | kafka 47% · postgres 64% | 0.53 of 2 | server 0.92 | 1.21 | 91.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 18 s | generator-overload 160 |
| r5-base | base | 1686.7/s | 1686.7/s | 67467 | 0 | 225.3 ms | 741.4 ms | 995.3 ms | n/a | 1777.7 ms | 0.90 cores | kafka 48% · postgres 64% | 0.55 of 2 | server 0.90 | 1.20 | 91.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 11 s | - |
| r5-head | head | 1396.7/s | 1396.7/s | 55867 | 0 | 42.0 ms | 249.9 ms | 395.3 ms | n/a | 589.8 ms | 0.87 cores | kafka 48% · postgres 67% | 0.50 of 2 | server 0.87 | 1.45 | 93.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | - |
| e1-base | base | 1396.7/s | 1396.7/s | 55867 | 0 | 51.7 ms | 553.0 ms | 880.6 ms | n/a | 1220.6 ms | 0.90 cores | kafka 48% · postgres 64% | 0.49 of 2 | server 0.90 | 1.44 | 93.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | - |
| e1-head | head | 1396.7/s | 1396.7/s | 55867 | 0 | 53.2 ms | 376.8 ms | 581.6 ms | n/a | 794.6 ms | 0.92 cores | kafka 46% · postgres 64% | 0.51 of 2 | server 0.92 | 1.45 | 95.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 5 s | - |
| e2-head | head | 1396.7/s | 1396.7/s | 55867 | 0 | 47.6 ms | 671.7 ms | 1073.2 ms | n/a | 1384.4 ms | 0.87 cores | kafka 45% · postgres 68% | 0.49 of 2 | server 0.87 | 1.43 | 93.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | - |
| e2-base | base | 1396.7/s | 1396.7/s | 55867 | 0 | 54.8 ms | 518.1 ms | 782.3 ms | n/a | 1056.8 ms | 0.90 cores | kafka 50% · postgres 62% | 0.49 of 2 | server 0.90 | 1.45 | 94.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | - |
| e3-base | base | 1396.7/s | 1396.7/s | 55867 | 0 | 59.6 ms | 471.0 ms | 585.7 ms | n/a | 696.3 ms | 0.91 cores | kafka 46% · postgres 65% | 0.49 of 2 | server 0.91 | 1.44 | 93.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 8 s | - |
| e3-head | head | 1396.7/s | 1396.7/s | 55867 | 0 | 47.6 ms | 288.8 ms | 446.5 ms | n/a | 692.2 ms | 0.90 cores | kafka 46% · postgres 65% | 0.50 of 2 | server 0.90 | 1.44 | 94.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | - |
| e4-head | head | 1396.7/s | 1396.7/s | 55867 | 0 | 58.4 ms | 479.2 ms | 622.6 ms | n/a | 774.1 ms | 0.89 cores | kafka 48% · postgres 66% | 0.48 of 2 | server 0.89 | 1.45 | 94.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | - |
| e4-base | base | 1396.7/s | 1396.7/s | 55867 | 0 | 31.4 ms | 258.0 ms | 344.1 ms | n/a | 440.3 ms | 0.89 cores | kafka 45% · postgres 68% | 0.49 of 2 | server 0.89 | 1.45 | 95.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.828 | 0.143 | 0.562 | 0.698 | - | 0.761 | 1.201 | 1.025 |
| 2 | 0.828 | 0.187 | 0.324 | 0.673 | - | 0.811 | 1.197 | 1.030 |
| 3 | 0.828 | 0.188 | 0.372 | 0.394 | - | 0.418 | 1.191 | 1.047 |
| 4 | 0.830 | 0.236 | 0.425 | 0.459 | - | 0.461 | 1.195 | 1.029 |
| 5 | 0.828 | 0.186 | 0.337 | 0.397 | - | 0.332 | 1.205 | 1.023 |

Pairs at one rate for both arms, 1396.7/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.030 | 0.681 | 0.660 | - | 0.651 | 1.002 | 1.021 |
| 2 | 1.000 | 0.869 | 1.296 | 1.372 | - | 1.310 | 0.991 | 0.995 |
| 3 | 1.000 | 0.798 | 0.613 | 0.762 | - | 0.994 | 1.002 | 1.013 |
| 4 | 1.000 | 1.861 | 1.857 | 1.810 | - | 1.758 | 1.000 | 0.998 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-pg-uniform@100k | r1-head | steady | **p99 > 10× p50** | p50 42.0 ms, p99 671.7 ms |
| e1-pg-uniform@100k | r2-head | steady | **p99 > 10× p50** | p50 58.4 ms, p99 716.8 ms |
| e1-pg-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +42.8% (±25.0%) |
| e1-pg-uniform@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +60.1% (±25.0%) |
| e1-pg-uniform@100k | r4-head | steady | **unstable** | second half against first, beyond the default limits: p50 +314.4% (±25.0%) |
| e1-pg-uniform@100k | r5-base | steady | **unstable** | second half against first, beyond the default limits: p50 +49.0% (±25.0%) |
| e1-pg-uniform@100k | r5-head | steady | **unstable** | second half against first, beyond the default limits: p50 +157.8% (±25.0%) |
| e1-pg-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +105.1% (±25.0%) |
| e1-pg-uniform@100k | e1-base | steady | **p99 > 10× p50** | p50 51.7 ms, p99 880.6 ms |
| e1-pg-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -58.1% (±25.0%) |
| e1-pg-uniform@100k | e1-head | steady | **p99 > 10× p50** | p50 53.2 ms, p99 581.6 ms |
| e1-pg-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.8% (±25.0%) |
| e1-pg-uniform@100k | e2-head | steady | **p99 > 10× p50** | p50 47.6 ms, p99 1073.2 ms |
| e1-pg-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +31.8% (±25.0%) |
| e1-pg-uniform@100k | e2-base | steady | **p99 > 10× p50** | p50 54.8 ms, p99 782.3 ms |
| e1-pg-uniform@100k | e3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -51.9% (±25.0%) |
| e1-pg-uniform@100k | e4-head | steady | **unstable** | second half against first, beyond the default limits: p50 +81.1% (±25.0%) |
| e1-pg-uniform@100k | e4-head | steady | **p99 > 10× p50** | p50 58.4 ms, p99 622.6 ms |
| e1-pg-uniform@100k | e4-base | steady | **unstable** | second half against first, beyond the default limits: p50 -45.5% (±25.0%) |
| e1-pg-uniform@100k | e4-base | steady | **p99 > 10× p50** | p50 31.4 ms, p99 344.1 ms |


### e3-pg@100k

E3 saga chain on PostgreSQL: head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 4 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (66–71 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 555.8/s | 64 | 444.6/s | 80% |
| head | 751.0/s | 1024 | 450.6/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 444.6/s | 444.6/s | 8892 | 0 | 456.7 ms | 2293.8 ms | 2637.8 ms | n/a | 3194.9 ms | 0.83 cores | kafka 52% · postgres 68% | 0.40 of 2 | server 0.83 | 4.56 | 263.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | - |
| probe1-head | head | 600.8/s | 529.5/s | 10591 | 1425 | 2981.9 ms | 5210.1 ms | 5701.6 ms | n/a | 6029.3 ms | 1.04 cores | kafka 43% · postgres 51% | 0.40 of 2 | server 1.04 | 3.74 | 214.5 KB | - | datasets 8 of 8 · default 1–2 of 5, lowered 1, pressed 17 s | generator-overload 1417 · http-504-wow.wait.timeout 8 |
| probe2-head | head | 450.6/s | 450.6/s | 9012 | 0 | 172.0 ms | 532.5 ms | 688.1 ms | n/a | 815.1 ms | 0.81 cores | kafka 42% · postgres 76% | 0.35 of 2 | server 0.81 | 4.40 | 252.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 2 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 444.6/s | 444.6/s | 17784 | 0 | 55.8 ms | 313.3 ms | 432.1 ms | n/a | 561.2 ms | 0.78 cores | kafka 48% · postgres 75% | 0.19 of 2 | server 0.78 | 4.52 | 251.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| r1-head | head | 450.6/s | 450.6/s | 18024 | 0 | 67.1 ms | 268.3 ms | 423.9 ms | n/a | 1196.0 ms | 0.83 cores | kafka 49% · postgres 69% | 0.19 of 2 | server 0.83 | 4.47 | 251.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 2 s | - |
| r2-head | head | 450.6/s | 450.6/s | 18024 | 0 | 51.5 ms | 475.1 ms | 696.3 ms | n/a | 819.2 ms | 0.82 cores | kafka 48% · postgres 71% | 0.19 of 2 | server 0.82 | 4.45 | 250.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| r2-base | base | 444.6/s | 444.6/s | 17784 | 0 | 40.4 ms | 444.4 ms | 655.4 ms | n/a | 774.1 ms | 0.82 cores | kafka 49% · postgres 70% | 0.19 of 2 | server 0.82 | 4.51 | 253.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| r3-base | base | 444.6/s | 444.6/s | 17784 | 0 | 38.9 ms | 232.4 ms | 319.5 ms | n/a | 428.0 ms | 0.79 cores | kafka 49% · postgres 72% | 0.19 of 2 | server 0.79 | 4.52 | 253.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r3-head | head | 450.6/s | 450.6/s | 18024 | 0 | 33.8 ms | 174.1 ms | 244.7 ms | n/a | 354.3 ms | 0.81 cores | kafka 48% · postgres 72% | 0.20 of 2 | server 0.81 | 4.46 | 255.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r4-head | head | 450.6/s | 450.6/s | 18024 | 0 | 35.8 ms | 181.2 ms | 257.0 ms | n/a | 374.8 ms | 0.81 cores | kafka 49% · postgres 71% | 0.19 of 2 | server 0.81 | 4.46 | 253.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r4-base | base | 444.6/s | 444.6/s | 17784 | 0 | 37.4 ms | 254.0 ms | 544.8 ms | n/a | 684.0 ms | 0.79 cores | kafka 49% · postgres 73% | 0.19 of 2 | server 0.79 | 4.51 | 250.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 2 s | - |
| r5-base | base | 444.6/s | 444.6/s | 17784 | 0 | 36.6 ms | 321.5 ms | 421.9 ms | n/a | 501.8 ms | 0.81 cores | kafka 49% · postgres 71% | 0.19 of 2 | server 0.81 | 4.52 | 254.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| r5-head | head | 450.6/s | 450.6/s | 18024 | 0 | 36.4 ms | 242.7 ms | 415.7 ms | n/a | 520.2 ms | 0.82 cores | kafka 49% · postgres 70% | 0.20 of 2 | server 0.82 | 4.45 | 253.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-base | base | 444.6/s | 444.6/s | 17784 | 0 | 36.1 ms | 325.6 ms | 671.7 ms | n/a | 827.4 ms | 0.78 cores | kafka 50% · postgres 74% | 0.19 of 2 | server 0.78 | 4.52 | 248.3 KB | - | datasets 8 of 8 · default 3 of 5, lowered 0, pressed 2 s | - |
| e1-head | head | 444.6/s | 444.6/s | 17784 | 0 | 35.8 ms | 385.0 ms | 565.2 ms | n/a | 704.5 ms | 0.83 cores | kafka 48% · postgres 69% | 0.19 of 2 | server 0.83 | 4.52 | 252.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| e2-head | head | 444.6/s | 444.6/s | 17784 | 0 | 42.2 ms | 201.7 ms | 264.2 ms | n/a | 342.0 ms | 0.81 cores | kafka 49% · postgres 71% | 0.19 of 2 | server 0.81 | 4.53 | 252.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e2-base | base | 444.6/s | 444.6/s | 17784 | 0 | 45.8 ms | 216.1 ms | 290.8 ms | n/a | 426.0 ms | 0.80 cores | kafka 49% · postgres 71% | 0.20 of 2 | server 0.80 | 4.51 | 251.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e3-base | base | 444.6/s | 444.6/s | 17784 | 0 | 30.2 ms | 191.5 ms | 274.4 ms | n/a | 395.3 ms | 0.79 cores | kafka 49% · postgres 73% | 0.18 of 2 | server 0.79 | 4.53 | 253.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e3-head | head | 444.6/s | 444.6/s | 17784 | 0 | 43.0 ms | 228.4 ms | 290.8 ms | n/a | 358.4 ms | 0.79 cores | kafka 48% · postgres 75% | 0.19 of 2 | server 0.79 | 4.54 | 253.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e4-head | head | 444.6/s | 444.6/s | 17784 | 0 | 53.0 ms | 415.7 ms | 585.7 ms | n/a | 704.5 ms | 0.82 cores | kafka 48% · postgres 70% | 0.19 of 2 | server 0.82 | 4.52 | 251.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 2 s | - |
| e4-base | base | 444.6/s | 444.6/s | 17784 | 0 | 57.3 ms | 262.1 ms | 333.8 ms | n/a | 434.2 ms | 0.78 cores | kafka 49% · postgres 74% | 0.19 of 2 | server 0.78 | 4.53 | 248.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.013 | 1.202 | 0.856 | 0.981 | - | 2.131 | 0.989 | 1.002 |
| 2 | 1.013 | 1.272 | 1.069 | 1.063 | - | 1.058 | 0.988 | 0.989 |
| 3 | 1.013 | 0.868 | 0.749 | 0.766 | - | 0.828 | 0.988 | 1.006 |
| 4 | 1.013 | 0.959 | 0.714 | 0.472 | - | 0.548 | 0.989 | 1.012 |
| 5 | 1.013 | 0.993 | 0.755 | 0.985 | - | 1.037 | 0.985 | 0.997 |

Pairs at one rate for both arms, 444.6/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.993 | 1.182 | 0.841 | - | 0.851 | 1.000 | 1.019 |
| 2 | 1.000 | 0.922 | 0.934 | 0.908 | - | 0.803 | 1.003 | 1.006 |
| 3 | 1.000 | 1.424 | 1.193 | 1.060 | - | 0.907 | 1.002 | 1.003 |
| 4 | 1.000 | 0.924 | 1.586 | 1.755 | - | 1.623 | 0.997 | 1.013 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -58.0% (±25.0%) |
| e3-pg@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -38.7% (±25.0%) |
| e3-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -69.8% (±25.0%) |
| e3-pg@100k | r2-head | steady | **p99 > 10× p50** | p50 51.5 ms, p99 696.3 ms |
| e3-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -60.0% (±25.0%) |
| e3-pg@100k | r2-base | steady | **p99 > 10× p50** | p50 40.4 ms, p99 655.4 ms |
| e3-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +108.1% (±25.0%) |
| e3-pg@100k | r4-head | steady | **unstable** | second half against first, beyond the default limits: p50 -34.3% (±25.0%) |
| e3-pg@100k | r4-base | steady | **unstable** | second half against first, beyond the default limits: p50 -34.5% (±25.0%) |
| e3-pg@100k | r4-base | steady | **p99 > 10× p50** | p50 37.4 ms, p99 544.8 ms |
| e3-pg@100k | r5-base | steady | **unstable** | second half against first, beyond the default limits: p50 -64.4% (±25.0%) |
| e3-pg@100k | r5-base | steady | **p99 > 10× p50** | p50 36.6 ms, p99 421.9 ms |
| e3-pg@100k | r5-head | steady | **p99 > 10× p50** | p50 36.4 ms, p99 415.7 ms |
| e3-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -36.1% (±25.0%) |
| e3-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 36.1 ms, p99 671.7 ms |
| e3-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 35.8 ms, p99 565.2 ms |
| e3-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -37.1% (±25.0%) |
| e3-pg@100k | e3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -30.7% (±25.0%) |
| e3-pg@100k | e3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -57.0% (±25.0%) |
| e3-pg@100k | e4-head | steady | **unstable** | second half against first, beyond the default limits: p50 -34.0% (±25.0%) |
| e3-pg@100k | e4-head | steady | **p99 > 10× p50** | p50 53.0 ms, p99 585.7 ms |



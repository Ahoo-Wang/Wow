# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `main` c48c5d519192ec4252bcdfb23d67b4cacbeef2c4; V10 baseline: `v10.0.0-alpha.4` c57cedbc285c911cce34018294a86587b16120b1; harness and load generator from head.

Tier: L3; coverage: e1-mongo-uniform@100k, e1-mongo-uniform@1k, e1-mongo-zipf@100k, e1-mongo-zipf@1k, e1-pg-uniform@100k, e1-pg-uniform@1k, e2-mongo@100k, e2-mongo@1k, e3-mongo@100k, e3-mongo@1k, e3-pg@100k, e3-pg@1k, mixed-mongo@100k, mixed-mongo@1k, mixed-pg@100k, mixed-pg@1k, large-mongo@100k, large-mongo@1k, large-pg@100k, large-pg@1k.

Each suite ran on one runner of its own, so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +25.3% (±25.0%) |
| e1-mongo-uniform@100k | r1-head | steady | **p99 > 10× p50** | p50 5.8 ms, p99 122.9 ms |
| e1-mongo-uniform@100k | r2-head | steady | **p99 > 10× p50** | p50 5.2 ms, p99 134.1 ms |
| e1-mongo-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +82.7% (±25.0%) |
| e1-mongo-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -25.2% (±25.0%) |
| e1-mongo-uniform@100k | r3-head | steady | **p99 > 10× p50** | p50 5.1 ms, p99 128.0 ms |
| e1-mongo-uniform@100k | e1-head | steady | **p99 > 10× p50** | p50 2.7 ms, p99 62.5 ms |
| e1-mongo-uniform@100k | e2-head | steady | **p99 > 10× p50** | p50 2.7 ms, p99 77.3 ms |
| e1-mongo-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +38.0% (±25.0%) |
| e1-mongo-uniform@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -75.7% (±25.0%) |
| e1-mongo-uniform@1k | r1-base | steady | **p99 > 10× p50** | p50 48.9 ms, p99 614.4 ms |
| e1-mongo-uniform@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.1% (±25.0%) |
| e1-mongo-uniform@1k | r1-head | steady | **p99 > 10× p50** | p50 12.7 ms, p99 1826.8 ms |
| e1-mongo-uniform@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -65.9% (±25.0%) |
| e1-mongo-uniform@1k | r2-head | steady | **p99 > 10× p50** | p50 19.7 ms, p99 864.3 ms |
| e1-mongo-uniform@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -83.8% (±25.0%) |
| e1-mongo-uniform@1k | r2-base | steady | **p99 > 10× p50** | p50 79.4 ms, p99 925.7 ms |
| e1-mongo-uniform@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -67.3% (±25.0%) |
| e1-mongo-uniform@1k | r3-base | steady | **p99 > 10× p50** | p50 26.9 ms, p99 460.8 ms |
| e1-mongo-uniform@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -50.5% (±25.0%) |
| e1-mongo-uniform@1k | r3-head | steady | **p99 > 10× p50** | p50 7.3 ms, p99 337.9 ms |
| e1-mongo-uniform@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -82.7% (±25.0%) |
| e1-mongo-uniform@1k | e1-base | steady | **p99 > 10× p50** | p50 50.4 ms, p99 581.6 ms |
| e1-mongo-uniform@1k | e1-head | steady | **p99 > 10× p50** | p50 2.9 ms, p99 126.5 ms |
| e1-mongo-uniform@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.5% (±25.0%) |
| e1-mongo-uniform@1k | e2-head | steady | **p99 > 10× p50** | p50 3.5 ms, p99 153.6 ms |
| e1-mongo-uniform@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -69.6% (±25.0%) |
| e1-mongo-uniform@1k | e2-base | steady | **p99 > 10× p50** | p50 46.3 ms, p99 1003.5 ms |
| e1-mongo-zipf@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -30.8% (±25.0%) |
| e1-mongo-zipf@100k | r1-base | steady | **p99 > 10× p50** | p50 12.4 ms, p99 1335.3 ms |
| e1-mongo-zipf@100k | r1-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r1-head | steady | **load generator saturated** | 0.95 of 1 cores: the run measured the generator |
| e1-mongo-zipf@100k | r1-head | steady | **error spike** | 627 of 50940 failed: generator-overload 627 |
| e1-mongo-zipf@100k | r2-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@100k | r2-head | steady | **load generator saturated** | 0.94 of 1 cores: the run measured the generator |
| e1-mongo-zipf@100k | r2-base | steady | **p99 > 10× p50** | p50 13.1 ms, p99 901.1 ms |
| e1-mongo-zipf@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -43.0% (±25.0%) |
| e1-mongo-zipf@100k | r3-base | steady | **p99 > 10× p50** | p50 12.2 ms, p99 782.3 ms |
| e1-mongo-zipf@100k | r3-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r3-head | steady | **load generator saturated** | 0.94 of 1 cores: the run measured the generator |
| e1-mongo-zipf@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -57.3% (±25.0%) |
| e1-mongo-zipf@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -56.7% (±25.0%) |
| e1-mongo-zipf@100k | e1-base | steady | **p99 > 10× p50** | p50 14.5 ms, p99 1646.6 ms |
| e1-mongo-zipf@100k | e2-base | steady | **p99 > 10× p50** | p50 17.2 ms, p99 3145.7 ms |
| e1-mongo-zipf@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -68.2% (±25.0%) |
| e1-mongo-zipf@1k | r1-base | steady | **p99 > 10× p50** | p50 6.0 ms, p99 6553.6 ms |
| e1-mongo-zipf@1k | r1-head | steady | **tail read the store** | overflow=5 |
| e1-mongo-zipf@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -33.3% (±25.0%) |
| e1-mongo-zipf@1k | r1-head | steady | **p99 > 10× p50** | p50 31.2 ms, p99 471.0 ms |
| e1-mongo-zipf@1k | r2-head | steady | **tail read the store** | overflow=4 |
| e1-mongo-zipf@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -71.4% (±25.0%) |
| e1-mongo-zipf@1k | r2-head | steady | **p99 > 10× p50** | p50 26.2 ms, p99 2736.1 ms |
| e1-mongo-zipf@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -69.9% (±25.0%) |
| e1-mongo-zipf@1k | r2-base | steady | **p99 > 10× p50** | p50 7.7 ms, p99 2785.3 ms |
| e1-mongo-zipf@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -48.2% (±25.0%) |
| e1-mongo-zipf@1k | r3-base | steady | **p99 > 10× p50** | p50 6.0 ms, p99 4882.4 ms |
| e1-mongo-zipf@1k | r3-head | steady | **tail read the store** | overflow=4 |
| e1-mongo-zipf@1k | r3-head | steady | **p99 > 10× p50** | p50 31.4 ms, p99 1171.5 ms |
| e1-mongo-zipf@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -86.8% (±25.0%) |
| e1-mongo-zipf@1k | e1-base | steady | **p99 > 10× p50** | p50 6.6 ms, p99 7536.6 ms |
| e1-mongo-zipf@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -34.6% (±25.0%) |
| e1-mongo-zipf@1k | e1-head | steady | **p99 > 10× p50** | p50 2.3 ms, p99 183.3 ms |
| e1-mongo-zipf@1k | e2-head | steady | **p99 > 10× p50** | p50 2.8 ms, p99 288.8 ms |
| e1-mongo-zipf@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +451.0% (±25.0%) |
| e1-mongo-zipf@1k | e2-base | steady | **p99 > 10× p50** | p50 20.1 ms, p99 4718.6 ms |
| e1-mongo-zipf@1k | probe2-head | overload (probe) | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | probe3-head | overload (probe) | **tail read the store** | overflow=1 |
| e1-pg-uniform@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -98.4% (±25.0%) |
| e1-pg-uniform@100k | r1-base | steady | **p99 > 10× p50** | p50 20.7 ms, p99 2089.0 ms |
| e1-pg-uniform@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -39.8% (±25.0%) |
| e1-pg-uniform@100k | r1-head | steady | **p99 > 10× p50** | p50 2.4 ms, p99 110.6 ms |
| e1-pg-uniform@100k | r2-head | steady | **p99 > 10× p50** | p50 3.5 ms, p99 166.9 ms |
| e1-pg-uniform@100k | r2-base | steady | **load generator saturated** | 0.90 of 1 cores: the run measured the generator |
| e1-pg-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -90.4% (±25.0%) |
| e1-pg-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -99.5% (±25.0%) |
| e1-pg-uniform@100k | r3-base | steady | **p99 > 10× p50** | p50 172.0 ms, p99 3358.7 ms |
| e1-pg-uniform@100k | r3-head | steady | **p99 > 10× p50** | p50 2.6 ms, p99 140.3 ms |
| e1-pg-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -99.1% (±25.0%) |
| e1-pg-uniform@100k | e1-base | steady | **p99 > 10× p50** | p50 72.7 ms, p99 2424.8 ms |
| e1-pg-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.5% (±25.0%) |
| e1-pg-uniform@100k | e1-head | steady | **p99 > 10× p50** | p50 2.4 ms, p99 149.5 ms |
| e1-pg-uniform@100k | e2-head | steady | **p99 > 10× p50** | p50 1.5 ms, p99 106.5 ms |
| e1-pg-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -89.6% (±25.0%) |
| e1-pg-uniform@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -93.9% (±25.0%) |
| e1-pg-uniform@1k | r1-base | steady | **p99 > 10× p50** | p50 10.3 ms, p99 4423.7 ms |
| e1-pg-uniform@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -99.0% (±25.0%) |
| e1-pg-uniform@1k | r1-head | steady | **p99 > 10× p50** | p50 321.5 ms, p99 7241.7 ms |
| e1-pg-uniform@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -98.2% (±25.0%) |
| e1-pg-uniform@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -52.3% (±25.0%) |
| e1-pg-uniform@1k | r2-base | steady | **p99 > 10× p50** | p50 4.1 ms, p99 231.4 ms |
| e1-pg-uniform@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: throughput +12.4% (±5.0%), p50 -82.2% (±25.0%) |
| e1-pg-uniform@1k | r3-base | steady | **error spike** | 1550 of 28180 failed: http-504-wow.wait.timeout 788 · http-429-wow.command.busy 762 |
| e1-pg-uniform@1k | r3-base | steady | **throughput dip** | 1 s below half the rate (lowest 295/s) |
| e1-pg-uniform@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -99.1% (±25.0%) |
| e1-pg-uniform@1k | r3-head | steady | **p99 > 10× p50** | p50 139.3 ms, p99 6750.2 ms |
| e1-pg-uniform@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -38.6% (±25.0%) |
| e1-pg-uniform@1k | e1-base | steady | **p99 > 10× p50** | p50 4.0 ms, p99 133.1 ms |
| e1-pg-uniform@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.4% (±25.0%) |
| e1-pg-uniform@1k | e1-head | steady | **p99 > 10× p50** | p50 3.4 ms, p99 245.8 ms |
| e1-pg-uniform@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -56.5% (±25.0%) |
| e1-pg-uniform@1k | e2-head | steady | **p99 > 10× p50** | p50 5.0 ms, p99 181.2 ms |
| e1-pg-uniform@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -63.1% (±25.0%) |
| e1-pg-uniform@1k | e2-base | steady | **p99 > 10× p50** | p50 6.4 ms, p99 909.3 ms |
| e1-pg-uniform@1k | probe1-base | overload (probe) | **tail read the store** | gap=5 |
| e1-pg-uniform@1k | probe2-base | overload (probe) | **tail read the store** | gap=10 |
| e1-pg-uniform@1k | probe3-base | overload (probe) | **tail read the store** | gap=10 |
| e1-pg-uniform@1k | probe4-base | overload (probe) | **tail read the store** | gap=2 |
| e2-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -60.2% (±25.0%) |
| e2-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -31.0% (±25.0%) |
| e2-mongo@100k | r1-head | steady | **p99 > 10× p50** | p50 28.4 ms, p99 1556.5 ms |
| e2-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -52.4% (±25.0%) |
| e2-mongo@100k | r2-head | steady | **p99 > 10× p50** | p50 25.7 ms, p99 1581.1 ms |
| e2-mongo@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -69.8% (±25.0%) |
| e2-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -53.6% (±25.0%) |
| e2-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -40.7% (±25.0%) |
| e2-mongo@100k | r3-head | steady | **p99 > 10× p50** | p50 20.6 ms, p99 1359.9 ms |
| e2-mongo@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -53.4% (±25.0%) |
| e2-mongo@100k | e1-head | steady | **p99 > 10× p50** | p50 12.7 ms, p99 138.2 ms |
| e2-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 14.1 ms, p99 152.6 ms |
| e2-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -50.8% (±25.0%) |
| e2-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -93.0% (±25.0%) |
| e2-mongo@1k | r1-base | steady | **p99 > 10× p50** | p50 84.0 ms, p99 1196.0 ms |
| e2-mongo@1k | r1-head | steady | **load generator saturated** | 0.95 of 1 cores: the run measured the generator |
| e2-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: throughput +31.6% (±5.0%) |
| e2-mongo@1k | r1-head | steady | **error spike** | 4945 of 41228 failed: generator-overload 4945 |
| e2-mongo@1k | r1-head | steady | **throughput dip** | 4 s below half the rate (lowest 1/s) |
| e2-mongo@1k | r2-head | steady | **load generator saturated** | 0.95 of 1 cores: the run measured the generator |
| e2-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -63.0% (±25.0%) |
| e2-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -75.4% (±25.0%) |
| e2-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -96.7% (±25.0%) |
| e2-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -86.7% (±25.0%) |
| e2-mongo@1k | e1-base | steady | **p99 > 10× p50** | p50 84.5 ms, p99 1605.6 ms |
| e2-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -68.6% (±25.0%) |
| e2-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 18.0 ms, p99 266.2 ms |
| e2-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -63.8% (±25.0%) |
| e2-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 18.9 ms, p99 497.7 ms |
| e2-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -41.6% (±25.0%) |
| e3-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -28.4% (±25.0%) |
| e3-mongo@100k | r2-head | steady | **p99 > 10× p50** | p50 39.4 ms, p99 454.7 ms |
| e3-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -25.6% (±25.0%) |
| e3-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +28.5% (±25.0%) |
| e3-mongo@100k | e1-head | steady | **p99 > 10× p50** | p50 11.5 ms, p99 153.6 ms |
| e3-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 10.8 ms, p99 136.2 ms |
| e3-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -46.5% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -48.8% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 33.8 ms, p99 565.2 ms |
| e3-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -53.5% (±25.0%) |
| e3-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 24.6 ms, p99 374.8 ms |
| e3-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -36.8% (±25.0%) |
| e3-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -42.0% (±25.0%) |
| e3-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -46.6% (±25.0%) |
| e3-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 30.0 ms, p99 548.9 ms |
| e3-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -54.5% (±25.0%) |
| e3-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 15.6 ms, p99 423.9 ms |
| e3-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -35.6% (±25.0%) |
| e3-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 15.6 ms, p99 250.9 ms |
| e3-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -39.4% (±25.0%) |
| e3-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -25.3% (±25.0%) |
| e3-pg@100k | r2-head | steady | **p99 > 10× p50** | p50 53.2 ms, p99 614.4 ms |
| e3-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +47.0% (±25.0%) |
| e3-pg@100k | r2-base | steady | **p99 > 10× p50** | p50 8.2 ms, p99 401.4 ms |
| e3-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -37.6% (±25.0%) |
| e3-pg@100k | r3-base | steady | **p99 > 10× p50** | p50 6.3 ms, p99 331.8 ms |
| e3-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.2% (±25.0%) |
| e3-pg@100k | r3-head | steady | **p99 > 10× p50** | p50 62.0 ms, p99 647.2 ms |
| e3-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +88.3% (±25.0%) |
| e3-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 34.0 ms, p99 532.5 ms |
| e3-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -28.2% (±25.0%) |
| e3-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 6.2 ms, p99 242.7 ms |
| e3-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -50.7% (±25.0%) |
| e3-pg@100k | e2-head | steady | **p99 > 10× p50** | p50 4.4 ms, p99 180.2 ms |
| e3-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -28.1% (±25.0%) |
| e3-pg@100k | e2-base | steady | **p99 > 10× p50** | p50 32.0 ms, p99 593.9 ms |
| e3-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -44.9% (±25.0%) |
| e3-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 4.5 ms, p99 102.4 ms |
| e3-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -59.7% (±25.0%) |
| e3-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 29.8 ms, p99 458.8 ms |
| e3-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -37.5% (±25.0%) |
| e3-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 31.0 ms, p99 618.5 ms |
| e3-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -45.4% (±25.0%) |
| e3-pg@1k | r2-base | steady | **p99 > 10× p50** | p50 4.2 ms, p99 84.0 ms |
| e3-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -51.4% (±25.0%) |
| e3-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 4.3 ms, p99 116.7 ms |
| e3-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -57.5% (±25.0%) |
| e3-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 25.7 ms, p99 505.9 ms |
| e3-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -45.2% (±25.0%) |
| e3-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 3.6 ms, p99 63.2 ms |
| e3-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -31.7% (±25.0%) |
| e3-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 6.6 ms, p99 152.6 ms |
| e3-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.3% (±25.0%) |
| e3-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 4.5 ms, p99 112.1 ms |
| e3-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -51.3% (±25.0%) |
| e3-pg@1k | e2-base | steady | **p99 > 10× p50** | p50 5.3 ms, p99 119.8 ms |
| e3-pg@1k | probe1-base | overload (probe) | **tail read the store** | gap=3 |
| e3-pg@1k | probe2-base | overload (probe) | **tail read the store** | gap=2 |
| mixed-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -25.7% (±25.0%) |
| mixed-mongo@100k | r1-base | steady | **p99 > 10× p50** | p50 5.1 ms, p99 203.8 ms |
| mixed-mongo@100k | r1-head | steady | **p99 > 10× p50** | p50 4.2 ms, p99 153.6 ms |
| mixed-mongo@100k | r2-head | steady | **p99 > 10× p50** | p50 3.3 ms, p99 93.7 ms |
| mixed-mongo@100k | r2-base | steady | **p99 > 10× p50** | p50 7.6 ms, p99 286.7 ms |
| mixed-mongo@100k | r3-base | steady | **p99 > 10× p50** | p50 5.9 ms, p99 299.0 ms |
| mixed-mongo@100k | r3-head | steady | **p99 > 10× p50** | p50 3.5 ms, p99 86.5 ms |
| mixed-mongo@100k | e1-base | steady | **p99 > 10× p50** | p50 5.8 ms, p99 284.7 ms |
| mixed-mongo@100k | e1-head | steady | **p99 > 10× p50** | p50 2.4 ms, p99 67.6 ms |
| mixed-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 2.0 ms, p99 52.7 ms |
| mixed-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -34.2% (±25.0%) |
| mixed-mongo@100k | e2-base | steady | **p99 > 10× p50** | p50 6.6 ms, p99 235.5 ms |
| mixed-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -56.7% (±25.0%) |
| mixed-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 5.0 ms, p99 290.8 ms |
| mixed-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -48.0% (±25.0%) |
| mixed-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 3.8 ms, p99 184.3 ms |
| mixed-mongo@1k | r2-base | steady | **p99 > 10× p50** | p50 2.9 ms, p99 104.4 ms |
| mixed-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -51.0% (±25.0%) |
| mixed-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 4.2 ms, p99 216.1 ms |
| mixed-mongo@1k | e1-base | steady | **p99 > 10× p50** | p50 2.8 ms, p99 64.5 ms |
| mixed-mongo@1k | e2-base | steady | **p99 > 10× p50** | p50 2.9 ms, p99 55.3 ms |
| mixed-pg@100k | r1-head | steady | **load generator saturated** | 0.95 of 1 cores: the run measured the generator |
| mixed-pg@100k | r1-head | steady | **error spike** | 1515 of 57687 failed: generator-overload 1080 · E4-list:read-http-429 435 |
| mixed-pg@100k | r2-head | steady | **load generator saturated** | 0.96 of 1 cores: the run measured the generator |
| mixed-pg@100k | r2-head | steady | **error spike** | 724 of 57687 failed: E4-list:read-http-429 724 |
| mixed-pg@100k | r3-head | steady | **load generator saturated** | 0.96 of 1 cores: the run measured the generator |
| mixed-pg@100k | r3-head | steady | **error spike** | 1137 of 57687 failed: E4-list:read-http-429 832 · generator-overload 127 · E1:exception-ConnectException 88 · E4-state:exception-ConnectException 74 · E4-list:exception-ConnectException 16 |
| mixed-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -37.6% (±25.0%) |
| mixed-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 3.0 ms, p99 92.2 ms |
| mixed-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -46.6% (±25.0%) |
| mixed-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 2.7 ms, p99 89.6 ms |
| mixed-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -31.5% (±25.0%) |
| mixed-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -33.6% (±25.0%) |
| mixed-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.6% (±25.0%) |
| mixed-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 3.0 ms, p99 116.7 ms |
| mixed-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -29.1% (±25.0%) |
| mixed-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -29.0% (±25.0%) |
| mixed-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -29.4% (±25.0%) |
| mixed-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -36.0% (±25.0%) |
| mixed-pg@1k | probe2-base | overload (probe) | **tail read the store** | gap=1 |
| large-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: throughput -12.6% (±5.0%) |
| large-mongo@100k | r1-base | steady | **error spike** | 2234 of 24907 failed: http-504-wow.wait.timeout 1269 · http-429-wow.command.busy 883 · http-503-wow.command.unknown 80 · http-503-wow.storage.unavailable 2 |
| large-mongo@100k | r1-base | steady | **throughput dip** | 1 s below half the rate (lowest 282/s) |
| large-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -94.9% (±25.0%) |
| large-mongo@100k | r1-head | steady | **p99 > 10× p50** | p50 272.4 ms, p99 4259.8 ms |
| large-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: throughput +23.1% (±5.0%), p50 -55.5% (±25.0%) |
| large-mongo@100k | r2-head | steady | **error spike** | 4266 of 45428 failed: http-429-wow.command.busy 4266 |
| large-mongo@100k | r2-head | steady | **throughput dip** | 3 s below half the rate (lowest 21/s) |
| large-mongo@100k | r2-base | steady | **error spike** | 3763 of 24907 failed: http-429-wow.command.busy 1844 · http-504-wow.wait.timeout 1823 · http-503-wow.command.unknown 96 |
| large-mongo@100k | r2-base | steady | **throughput dip** | 2 s below half the rate (lowest 176/s) |
| large-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: throughput +5.1% (±5.0%) |
| large-mongo@100k | r3-base | steady | **error spike** | 4292 of 24907 failed: http-429-wow.command.busy 2071 · http-504-wow.wait.timeout 2026 · http-503-wow.command.unknown 193 · http-503-wow.storage.unavailable 2 |
| large-mongo@100k | r3-base | steady | **throughput dip** | 1 s below half the rate (lowest 262/s) |
| large-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: throughput +22.4% (±5.0%), p50 -64.3% (±25.0%) |
| large-mongo@100k | r3-head | steady | **error spike** | 4156 of 45428 failed: http-429-wow.command.busy 4156 |
| large-mongo@100k | r3-head | steady | **throughput dip** | 3 s below half the rate (lowest 63/s) |
| large-mongo@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: throughput +48.0% (±5.0%) |
| large-mongo@100k | e1-base | steady | **error spike** | 6095 of 24907 failed: http-429-wow.command.busy 3561 · http-504-wow.wait.timeout 2490 · http-503-wow.command.unknown 42 · http-503-wow.storage.unavailable 2 |
| large-mongo@100k | e1-base | steady | **throughput dip** | 6 s below half the rate (lowest 213/s) |
| large-mongo@100k | e1-head | steady | **p99 > 10× p50** | p50 8.3 ms, p99 176.1 ms |
| large-mongo@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -25.4% (±25.0%) |
| large-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 7.1 ms, p99 122.9 ms |
| large-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: throughput +10.5% (±5.0%) |
| large-mongo@100k | e2-base | steady | **error spike** | 8247 of 24907 failed: http-429-wow.command.busy 4656 · http-504-wow.wait.timeout 3507 · http-503-wow.command.unknown 82 · http-503-wow.storage.unavailable 2 |
| large-mongo@100k | e2-base | steady | **throughput dip** | 7 s below half the rate (lowest 164/s) |
| large-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -34.2% (±25.0%) |
| large-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -30.7% (±25.0%) |
| large-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 58.9 ms, p99 958.5 ms |
| large-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -70.1% (±25.0%) |
| large-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 82.4 ms, p99 2949.1 ms |
| large-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -31.3% (±25.0%) |
| large-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -50.0% (±25.0%) |
| large-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -63.4% (±25.0%) |
| large-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 91.6 ms, p99 1228.8 ms |
| large-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -60.6% (±25.0%) |
| large-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -41.7% (±25.0%) |
| large-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 21.4 ms, p99 380.9 ms |
| large-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -29.9% (±25.0%) |
| large-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 19.2 ms, p99 557.1 ms |
| large-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -63.7% (±25.0%) |
| large-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -38.3% (±25.0%) |
| large-pg@100k | r1-base | steady | **p99 > 10× p50** | p50 9.5 ms, p99 505.9 ms |
| large-pg@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -89.2% (±25.0%) |
| large-pg@100k | r1-head | steady | **p99 > 10× p50** | p50 178.2 ms, p99 2949.1 ms |
| large-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -88.3% (±25.0%) |
| large-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +259.5% (±25.0%) |
| large-pg@100k | r2-base | steady | **p99 > 10× p50** | p50 11.2 ms, p99 651.3 ms |
| large-pg@100k | r3-base | steady | **p99 > 10× p50** | p50 9.0 ms, p99 667.6 ms |
| large-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: throughput +5.5% (±5.0%), p50 -82.7% (±25.0%) |
| large-pg@100k | r3-head | steady | **error spike** | 1124 of 43384 failed: http-429-wow.command.busy 1124 |
| large-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +30.9% (±25.0%) |
| large-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 9.1 ms, p99 626.7 ms |
| large-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -31.3% (±25.0%) |
| large-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 3.1 ms, p99 280.6 ms |
| large-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -43.8% (±25.0%) |
| large-pg@100k | e2-head | steady | **p99 > 10× p50** | p50 3.5 ms, p99 409.6 ms |
| large-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +42.3% (±25.0%) |
| large-pg@100k | e2-base | steady | **p99 > 10× p50** | p50 10.7 ms, p99 692.2 ms |
| large-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -46.5% (±25.0%) |
| large-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 3.2 ms, p99 112.6 ms |
| large-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -82.2% (±25.0%) |
| large-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 58.1 ms, p99 1540.1 ms |
| large-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -74.9% (±25.0%) |
| large-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 58.9 ms, p99 974.8 ms |
| large-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -44.1% (±25.0%) |
| large-pg@1k | r2-base | steady | **p99 > 10× p50** | p50 3.3 ms, p99 69.1 ms |
| large-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -46.8% (±25.0%) |
| large-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 3.0 ms, p99 193.5 ms |
| large-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -73.0% (±25.0%) |
| large-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 64.5 ms, p99 806.9 ms |
| large-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -43.8% (±25.0%) |
| large-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 2.8 ms, p99 72.7 ms |
| large-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -40.1% (±25.0%) |
| large-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 2.9 ms, p99 106.5 ms |
| large-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -48.6% (±25.0%) |
| large-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 3.2 ms, p99 94.2 ms |
| large-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -62.8% (±25.0%) |
| large-pg@1k | e2-base | steady | **p99 > 10× p50** | p50 3.2 ms, p99 178.2 ms |
| large-pg@1k | probe1-base | overload (probe) | **tail read the store** | gap=24 |
| large-pg@1k | probe2-base | overload (probe) | **tail read the store** | gap=9 |
| large-pg@1k | probe3-base | overload (probe) | **tail read the store** | gap=3 |
| large-pg@1k | probe4-base | overload (probe) | **tail read the store** | gap=3 |

## Significance

Beyond noise: 0 of 60 ratios; 0 of 20 suites have an A/A noise threshold (test/benchmarks/noise.json). A ratio counts when its 95% bootstrap interval (over the measured seconds) clears the suite's threshold; without one it is `uncalibrated`.

| Suite | b/a | Metric | Rounds | Ratio [95% CI] | Noise | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | throughput | calibrated | 1.603 [1.603–1.603] | - | uncalibrated |
| e1-mongo-uniform@100k | head/base | p50 | equal | 0.011 [0.008–0.013] | - | uncalibrated |
| e1-mongo-uniform@100k | head/base | p99 | equal | 0.077 [0.024–0.055] | - | uncalibrated |
| e1-mongo-uniform@1k | head/base | throughput | calibrated | 1.786 [1.786–1.786] | - | uncalibrated |
| e1-mongo-uniform@1k | head/base | p50 | equal | 0.065 [0.038–0.102] | - | uncalibrated |
| e1-mongo-uniform@1k | head/base | p99 | equal | 0.177 [0.057–0.188] | - | uncalibrated |
| e1-mongo-zipf@100k | head/base | throughput | calibrated | 2.972 [2.945–2.990] | - | uncalibrated |
| e1-mongo-zipf@100k | head/base | p50 | equal | 0.166 [0.122–0.219] | - | uncalibrated |
| e1-mongo-zipf@100k | head/base | p99 | equal | 0.017 [0.018–0.059] | - | uncalibrated |
| e1-mongo-zipf@1k | head/base | throughput | calibrated | 3.864 [3.863–3.865] | - | uncalibrated |
| e1-mongo-zipf@1k | head/base | p50 | equal | 0.193 [0.124–0.390] | - | uncalibrated |
| e1-mongo-zipf@1k | head/base | p99 | equal | 0.039 [0.094–0.385] | - | uncalibrated |
| e1-pg-uniform@100k | head/base | throughput | calibrated | 0.986 [0.986–0.986] | - | uncalibrated |
| e1-pg-uniform@100k | head/base | p50 | equal | 0.008 [0.003–0.017] | - | uncalibrated |
| e1-pg-uniform@100k | head/base | p99 | equal | 0.055 [0.023–0.072] | - | uncalibrated |
| e1-pg-uniform@1k | head/base | throughput | calibrated | 1.534 [1.517–1.558] | - | uncalibrated |
| e1-pg-uniform@1k | head/base | p50 | equal | 0.809 [0.599–1.176] | - | uncalibrated |
| e1-pg-uniform@1k | head/base | p99 | equal | 0.410 [0.406–1.286] | - | uncalibrated |
| e2-mongo@100k | head/base | throughput | calibrated | 1.137 [1.137–1.137] | - | uncalibrated |
| e2-mongo@100k | head/base | p50 | equal | 0.006 [0.005–0.008] | - | uncalibrated |
| e2-mongo@100k | head/base | p99 | equal | 0.026 [0.015–0.029] | - | uncalibrated |
| e2-mongo@1k | head/base | throughput | calibrated | 1.672 [1.622–1.714] | - | uncalibrated |
| e2-mongo@1k | head/base | p50 | equal | 0.084 [0.053–0.159] | - | uncalibrated |
| e2-mongo@1k | head/base | p99 | equal | 0.263 [0.124–0.291] | - | uncalibrated |
| e3-mongo@100k | head/base | throughput | calibrated | 1.886 [1.885–1.886] | - | uncalibrated |
| e3-mongo@100k | head/base | p50 | equal | 0.015 [0.012–0.016] | - | uncalibrated |
| e3-mongo@100k | head/base | p99 | equal | 0.090 [0.027–0.053] | - | uncalibrated |
| e3-mongo@1k | head/base | throughput | calibrated | 1.363 [1.362–1.363] | - | uncalibrated |
| e3-mongo@1k | head/base | p50 | equal | 0.031 [0.023–0.038] | - | uncalibrated |
| e3-mongo@1k | head/base | p99 | equal | 0.171 [0.055–0.109] | - | uncalibrated |
| e3-pg@100k | head/base | throughput | calibrated | 1.928 [1.927–1.928] | - | uncalibrated |
| e3-pg@100k | head/base | p50 | equal | 0.159 [0.073–0.418] | - | uncalibrated |
| e3-pg@100k | head/base | p99 | equal | 0.375 [0.132–0.312] | - | uncalibrated |
| e3-pg@1k | head/base | throughput | calibrated | 1.653 [1.653–1.654] | - | uncalibrated |
| e3-pg@1k | head/base | p50 | equal | 1.251 [1.062–1.686] | - | uncalibrated |
| e3-pg@1k | head/base | p99 | equal | 1.446 [0.796–3.007] | - | uncalibrated |
| mixed-mongo@100k | head/base | throughput | calibrated | 1.700 [1.699–1.702] | - | uncalibrated |
| mixed-mongo@100k | head/base | p50 | equal | 0.359 [0.278–0.428] | - | uncalibrated |
| mixed-mongo@100k | head/base | p99 | equal | 0.231 [0.151–0.376] | - | uncalibrated |
| mixed-mongo@1k | head/base | throughput | calibrated | 2.059 [2.059–2.060] | - | uncalibrated |
| mixed-mongo@1k | head/base | p50 | equal | 0.564 [0.564–0.626] | - | uncalibrated |
| mixed-mongo@1k | head/base | p99 | equal | 0.268 [0.247–0.548] | - | uncalibrated |
| mixed-pg@100k | head/base | throughput | calibrated | 1.644 [1.633–1.654] | - | uncalibrated |
| mixed-pg@100k | head/base | p50 | equal | 0.715 [0.597–0.818] | - | uncalibrated |
| mixed-pg@100k | head/base | p99 | equal | 0.845 [0.469–0.931] | - | uncalibrated |
| mixed-pg@1k | head/base | throughput | calibrated | 1.289 [1.289–1.289] | - | uncalibrated |
| mixed-pg@1k | head/base | p50 | equal | 0.960 [0.876–1.127] | - | uncalibrated |
| mixed-pg@1k | head/base | p99 | equal | 0.805 [0.499–1.435] | - | uncalibrated |
| large-mongo@100k | head/base | throughput | calibrated | 1.984 [1.883–2.072] | - | uncalibrated |
| large-mongo@100k | head/base | p50 | equal | 0.001 [0.001–0.001] | - | uncalibrated |
| large-mongo@100k | head/base | p99 | equal | 0.015 [0.004–0.007] | - | uncalibrated |
| large-mongo@1k | head/base | throughput | calibrated | 1.503 [1.502–1.503] | - | uncalibrated |
| large-mongo@1k | head/base | p50 | equal | 0.048 [0.038–0.089] | - | uncalibrated |
| large-mongo@1k | head/base | p99 | equal | 0.136 [0.091–0.233] | - | uncalibrated |
| large-pg@100k | head/base | throughput | calibrated | 1.591 [1.573–1.605] | - | uncalibrated |
| large-pg@100k | head/base | p50 | equal | 0.333 [0.146–0.580] | - | uncalibrated |
| large-pg@100k | head/base | p99 | equal | 0.523 [0.224–0.721] | - | uncalibrated |
| large-pg@1k | head/base | throughput | calibrated | 1.501 [1.501–1.502] | - | uncalibrated |
| large-pg@1k | head/base | p50 | equal | 1.013 [0.891–1.183] | - | uncalibrated |
| large-pg@1k | head/base | p99 | equal | 0.800 [0.548–2.282] | - | uncalibrated |

## Runners

| Suite | CPU | Cores | Memory |
| --- | --- | --- | --- |
| e1-mongo-uniform@100k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB |
| e1-mongo-uniform@1k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB |
| e1-mongo-zipf@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| e1-mongo-zipf@1k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB |
| e1-pg-uniform@100k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB |
| e1-pg-uniform@1k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| e2-mongo@100k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB |
| e2-mongo@1k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB |
| e3-mongo@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| e3-mongo@1k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| e3-pg@100k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB |
| e3-pg@1k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| mixed-mongo@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| mixed-mongo@1k | Intel(R) Xeon(R) 6973P-C | 4 | 16 GB |
| mixed-pg@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| mixed-pg@1k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| large-mongo@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| large-mongo@1k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| large-pg@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |
| large-pg@1k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | base | ≥ 2222.4/s | 4096 | 1000.1/s | 45% |
| e1-mongo-uniform@100k | head | 3562.0/s | 256 | 1602.9/s | 45% |
| e1-mongo-uniform@1k | base | 2087.4/s | 1024 | 939.3/s | 45% |
| e1-mongo-uniform@1k | head | 3727.6/s | 128 | 1677.4/s | 45% |
| e1-mongo-zipf@100k | base | 532.5/s | 512 | 426.0/s | 80% |
| e1-mongo-zipf@100k | head | 2122.5/s | 512 | 1273.5/s | 60% |
| e1-mongo-zipf@1k | base | 441.0/s | 128 | 352.8/s | 80% |
| e1-mongo-zipf@1k | head | 3029.3/s | 512 | 1363.2/s | 45% |
| e1-pg-uniform@100k | base | 2487.0/s | 32 | 1989.6/s | 80% |
| e1-pg-uniform@100k | head | 4361.1/s | 128 | 1962.5/s | 45% |
| e1-pg-uniform@1k | base | 2087.4/s | 128 | 704.5/s | 34% (**not sustained**) |
| e1-pg-uniform@1k | head | 2358.3/s | 256 | 1061.2/s | 45% |
| e2-mongo@100k | base | 987.3/s | 2048 | 789.8/s | 80% |
| e2-mongo@100k | head | 1496.4/s | 256 | 897.8/s | 60% |
| e2-mongo@1k | base | 1314.9/s | 1024 | 591.7/s | 45% |
| e2-mongo@1k | head | 1717.8/s | 256 | 1030.7/s | 60% |
| e3-mongo@100k | base | 518.0/s | 2048 | 310.8/s | 60% |
| e3-mongo@100k | head | 976.8/s | 2048 | 586.1/s | 60% |
| e3-mongo@1k | base | 522.8/s | 2048 | 235.2/s | 45% |
| e3-mongo@1k | head | 949.5/s | 128 | 320.5/s | 34% |
| e3-pg@100k | base | 1250.4/s | 64 | 750.2/s | 60% |
| e3-pg@100k | head | 2410.1/s | 1024 | 1446.1/s | 60% |
| e3-pg@1k | base | 787.4/s | 64 | 265.7/s | 34% |
| e3-pg@1k | head | 1301.5/s | 1024 | 439.3/s | 34% |
| mixed-mongo@100k | base | 843.1/s | 64 | 505.9/s | 60% |
| mixed-mongo@100k | head | 1910.3/s | 256 | 859.6/s | 45% |
| mixed-mongo@1k | base | 1588.5/s | 128 | 536.1/s | 34% |
| mixed-mongo@1k | head | 3271.3/s | 128 | 1104.0/s | 34% (**not sustained**) |
| mixed-pg@100k | base | 1911.1/s | 64 | 860.0/s | 45% |
| mixed-pg@100k | head | 2403.6/s | 64 | 1442.2/s | 60% |
| mixed-pg@1k | base | 1738.4/s | 32 | 586.7/s | 34% |
| mixed-pg@1k | head | 2240.5/s | 64 | 756.2/s | 34% (**not sustained**) |
| large-mongo@100k | base | 1037.9/s | 2048 | 622.7/s | 60% |
| large-mongo@100k | head | 1419.6/s | 512 | 1135.7/s | 80% |
| large-mongo@1k | base | 1158.4/s | 2048 | 391.0/s | 34% |
| large-mongo@1k | head | 1740.6/s | 2048 | 587.5/s | 34% |
| large-pg@100k | base | 1126.1/s | 64 | 675.7/s | 60% |
| large-pg@100k | head | 1355.8/s | 128 | 1084.6/s | 80% |
| large-pg@1k | base | 1143.9/s | 64 | 386.1/s | 34% |
| large-pg@1k | head | 1288.1/s | 512 | 579.7/s | 45% |

## Paired ratios

`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.

| Suite | b/a | Rounds | Saturation | Throughput | p50 | p95 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | calibrated (unequal load) | ~1.603 | 1.603 (1.603–1.603, n=3) | 0.027 (0.020–0.028, n=3) | 0.086 (0.076–0.092, n=3) | 0.160 (0.106–0.161, n=3) | - | 0.229 (0.127–0.270, n=3) | 0.535 (0.525–0.540, n=3) | 0.448 (0.447–0.467, n=3) |
| e1-mongo-uniform@100k | head/base | equal rate 1000.1/s | ~1.603 | 1.000 (1.000–1.000, n=2) | 0.011 (0.010–0.012, n=2) | 0.028 (0.021–0.036, n=2) | 0.078 (0.063–0.093, n=2) | - | 0.108 (0.092–0.124, n=2) | 0.769 (0.760–0.777, n=2) | 0.541 (0.529–0.553, n=2) |
| e1-mongo-uniform@1k | head/base | calibrated (unequal load) | 1.786 | 1.786 (1.786–1.786, n=3) | 0.259 (0.248–0.273, n=3) | 1.015 (0.618–3.214, n=3) | 0.934 (0.733–2.973, n=3) | - | 0.627 (0.580–1.625, n=3) | 0.533 (0.532–0.533, n=3) | 0.516 (0.505–0.521, n=3) |
| e1-mongo-uniform@1k | head/base | equal rate 939.3/s | 1.786 | 1.000 (1.000–1.000, n=2) | 0.066 (0.057–0.075, n=2) | 0.093 (0.082–0.103, n=2) | 0.185 (0.153–0.217, n=2) | - | 0.215 (0.199–0.231, n=2) | 0.802 (0.796–0.808, n=2) | 0.621 (0.618–0.624, n=2) |
| e1-mongo-zipf@100k | head/base | calibrated (unequal load) | 3.986 | 2.975 (2.953–2.989, n=3) | 894.195 (363.819–902.671, n=3) | 34.667 (27.413–36.978, n=3) | 10.994 (10.555–19.782, n=3) | - | 7.896 (5.604–14.338, n=3) | 0.321 (0.319–0.330, n=3) | 0.395 (0.380–0.395, n=3) |
| e1-mongo-zipf@100k | head/base | equal rate 426.0/s | 3.986 | 1.000 (1.000–1.000, n=2) | 0.167 (0.159–0.175, n=2) | 0.024 (0.023–0.025, n=2) | 0.019 (0.014–0.024, n=2) | - | 0.041 (0.035–0.046, n=2) | 0.816 (0.809–0.822, n=2) | 0.551 (0.549–0.552, n=2) |
| e1-mongo-zipf@1k | head/base | calibrated (unequal load) | 6.869 | 3.864 (3.864–3.864, n=3) | 5.248 (3.417–5.270, n=3) | 0.992 (0.169–1.854, n=3) | 0.240 (0.072–0.982, n=3) | - | 0.344 (0.096–1.005, n=3) | 0.324 (0.303–0.326, n=3) | 0.406 (0.399–0.429, n=3) |
| e1-mongo-zipf@1k | head/base | equal rate 352.8/s | 6.869 | 1.000 (1.000–1.000, n=2) | 0.247 (0.140–0.354, n=2) | 0.074 (0.031–0.118, n=2) | 0.043 (0.024–0.061, n=2) | - | 0.051 (0.036–0.067, n=2) | 0.770 (0.756–0.783, n=2) | 0.546 (0.540–0.552, n=2) |
| e1-pg-uniform@100k | head/base | calibrated (unequal load) | 1.754 | 0.986 (0.986–0.986, n=3) | 0.015 (0.002–0.118, n=3) | 0.017 (0.015–0.018, n=3) | 0.042 (0.039–0.053, n=3) | - | 0.077 (0.075–0.148, n=3) | 0.953 (0.944–0.976, n=3) | 0.721 (0.719–0.729, n=3) |
| e1-pg-uniform@100k | head/base | equal rate 1962.5/s | 1.754 | 1.000 (1.000–1.000, n=2) | 0.018 (0.003–0.033, n=2) | 0.014 (0.010–0.018, n=2) | 0.055 (0.048–0.062, n=2) | - | 0.122 (0.118–0.127, n=2) | 0.897 (0.864–0.930, n=2) | 0.726 (0.717–0.736, n=2) |
| e1-pg-uniform@1k | head/base | calibrated (unequal load) | 1.130 | 1.506 (1.506–1.594, n=3) | 31.208 (0.123–418.830, n=3) | 3.799 (0.706–66.874, n=3) | 1.637 (0.682–39.080, n=3) | - | 0.989 (0.603–22.165, n=3) | 0.667 (0.613–0.713, n=3) | 0.745 (0.588–0.911, n=3) |
| e1-pg-uniform@1k | head/base | equal rate 704.5/s | 1.130 | 1.000 (1.000–1.000, n=2) | 0.815 (0.789–0.841, n=2) | 0.411 (0.185–0.638, n=2) | 1.023 (0.199–1.846, n=2) | - | 1.259 (0.117–2.400, n=2) | 0.979 (0.974–0.985, n=2) | 0.822 (0.801–0.843, n=2) |
| e2-mongo@100k | head/base | calibrated (unequal load) | 1.516 | 1.137 (1.137–1.137, n=3) | 0.011 (0.010–0.019, n=3) | 0.220 (0.175–0.226, n=3) | 0.273 (0.209–0.292, n=3) | - | 0.244 (0.184–0.261, n=3) | 0.783 (0.774–0.795, n=3) | 0.625 (0.624–0.631, n=3) |
| e2-mongo@100k | head/base | equal rate 789.8/s | 1.516 | 1.000 (1.000–1.000, n=2) | 0.007 (0.005–0.008, n=2) | 0.019 (0.017–0.021, n=2) | 0.027 (0.023–0.031, n=2) | - | 0.042 (0.039–0.044, n=2) | 0.877 (0.874–0.881, n=2) | 0.643 (0.630–0.656, n=2) |
| e2-mongo@1k | head/base | calibrated (unequal load) | 1.306 | 1.742 (1.533–1.742, n=3) | 60.939 (11.739–223.222, n=3) | 14.996 (8.115–28.255, n=3) | 11.218 (5.593–19.288, n=3) | - | 8.403 (3.025–12.465, n=3) | 0.597 (0.500–1.151, n=3) | 0.625 (0.532–0.641, n=3) |
| e2-mongo@1k | head/base | equal rate 591.7/s | 1.306 | 1.000 (1.000–1.000, n=2) | 0.133 (0.053–0.214, n=2) | 0.172 (0.129–0.214, n=2) | 0.275 (0.166–0.384, n=2) | - | 0.312 (0.247–0.377, n=2) | 0.934 (0.920–0.947, n=2) | 0.699 (0.680–0.718, n=2) |
| e3-mongo@100k | head/base | calibrated (unequal load) | 1.886 | 1.886 (1.886–1.886, n=3) | 0.049 (0.047–0.056, n=3) | 0.183 (0.166–0.187, n=3) | 0.212 (0.203–0.259, n=3) | - | 0.265 (0.200–0.350, n=3) | 0.514 (0.509–0.515, n=3) | 0.441 (0.437–0.448, n=3) |
| e3-mongo@100k | head/base | equal rate 310.8/s | 1.886 | 1.000 (1.000–1.000, n=2) | 0.015 (0.014–0.016, n=2) | 0.055 (0.049–0.061, n=2) | 0.090 (0.083–0.097, n=2) | - | 0.119 (0.116–0.123, n=2) | 0.903 (0.903–0.903, n=2) | 0.577 (0.567–0.587, n=2) |
| e3-mongo@1k | head/base | calibrated (unequal load) | 1.816 | 1.363 (1.363–1.363, n=3) | 0.052 (0.045–0.058, n=3) | 0.194 (0.155–0.206, n=3) | 0.215 (0.191–0.236, n=3) | - | 0.242 (0.189–0.312, n=3) | 0.713 (0.702–0.718, n=3) | 0.577 (0.561–0.581, n=3) |
| e3-mongo@1k | head/base | equal rate 235.2/s | 1.816 | 1.000 (1.000–1.000, n=2) | 0.031 (0.028–0.034, n=2) | 0.110 (0.102–0.118, n=2) | 0.169 (0.137–0.201, n=2) | - | 0.219 (0.194–0.244, n=2) | 0.937 (0.930–0.945, n=2) | 0.651 (0.650–0.651, n=2) |
| e3-pg@100k | head/base | calibrated (unequal load) | 1.928 | 1.928 (1.928–1.928, n=3) | 6.501 (0.033–9.779, n=3) | 1.763 (0.124–4.100, n=3) | 1.531 (0.162–1.951, n=3) | - | 1.367 (0.184–1.440, n=3) | 0.529 (0.489–0.533, n=3) | 0.644 (0.613–0.651, n=3) |
| e3-pg@100k | head/base | equal rate 750.2/s | 1.928 | 1.000 (1.000–1.000, n=2) | 0.159 (0.136–0.181, n=2) | 0.158 (0.124–0.191, n=2) | 0.380 (0.303–0.456, n=2) | - | 0.513 (0.508–0.519, n=2) | 0.921 (0.920–0.921, n=2) | 0.746 (0.745–0.747, n=2) |
| e3-pg@1k | head/base | calibrated (unequal load) | 1.653 | 1.653 (1.653–1.653, n=3) | 6.565 (5.957–7.335, n=3) | 8.265 (7.643–13.310, n=3) | 4.480 (4.333–7.366, n=3) | - | 2.793 (1.904–3.108, n=3) | 0.717 (0.715–0.717, n=3) | 0.783 (0.780–0.784, n=3) |
| e3-pg@1k | head/base | equal rate 265.7/s | 1.653 | 1.000 (1.000–1.000, n=2) | 1.349 (0.849–1.848, n=2) | 1.873 (0.691–3.056, n=2) | 1.674 (0.936–2.413, n=2) | - | 0.945 (0.777–1.113, n=2) | 1.053 (0.983–1.123, n=2) | 0.811 (0.810–0.812, n=2) |
| mixed-mongo@100k | head/base | calibrated (unequal load) | 2.266 | 1.699 (1.699–1.702, n=3) | 0.586 (0.433–0.824, n=3) | 0.278 (0.266–0.933, n=3) | 0.327 (0.289–0.754, n=3) | - | 0.376 (0.337–0.551, n=3) | 0.577 (0.556–0.592, n=3) | 0.531 (0.515–0.533, n=3) |
| mixed-mongo@100k | head/base | equal rate 505.9/s | 2.266 | 1.000 (1.000–1.000, n=2) | 0.363 (0.307–0.420, n=2) | 0.141 (0.111–0.170, n=2) | 0.231 (0.224–0.237, n=2) | - | 0.199 (0.181–0.217, n=2) | 0.826 (0.795–0.857, n=2) | 0.572 (0.571–0.573, n=2) |
| mixed-mongo@1k | head/base | calibrated (unequal load) | 2.059 | 2.059 (2.059–2.059, n=3) | 1.477 (1.317–1.696, n=3) | 6.927 (3.791–9.455, n=3) | 6.543 (1.765–6.844, n=3) | - | 5.634 (0.784–6.358, n=3) | 0.632 (0.622–0.667, n=3) | 0.644 (0.641–0.660, n=3) |
| mixed-mongo@1k | head/base | equal rate 536.1/s | 2.059 | 1.000 (1.000–1.000, n=2) | 0.565 (0.555–0.574, n=2) | 0.305 (0.283–0.328, n=2) | 0.264 (0.219–0.310, n=2) | - | 0.389 (0.337–0.442, n=2) | 0.844 (0.835–0.853, n=2) | 0.702 (0.690–0.715, n=2) |
| mixed-pg@100k | head/base | calibrated (unequal load) | 1.258 | 1.644 (1.633–1.656, n=3) | 5607.687 (4998.245–5946.130, n=3) | 1407.316 (1388.770–1464.048, n=3) | 634.615 (595.474–745.636, n=3) | - | 179.315 (145.232–197.058, n=3) | 0.611 (0.606–0.614, n=3) | 0.792 (0.782–0.830, n=3) |
| mixed-pg@100k | head/base | equal rate 860.0/s | 1.258 | 1.000 (1.000–1.000, n=2) | 0.715 (0.693–0.737, n=2) | 0.647 (0.610–0.685, n=2) | 0.859 (0.781–0.936, n=2) | - | 1.822 (0.883–2.760, n=2) | 0.927 (0.915–0.939, n=2) | 0.794 (0.790–0.799, n=2) |
| mixed-pg@1k | head/base | calibrated (unequal load) | 1.289 | 1.289 (1.289–1.289, n=3) | 1.771 (1.641–2.068, n=3) | 4.667 (4.449–6.985, n=3) | 3.704 (3.288–7.267, n=3) | - | 4.166 (1.653–8.102, n=3) | 0.957 (0.945–1.057, n=3) | 0.898 (0.878–0.898, n=3) |
| mixed-pg@1k | head/base | equal rate 586.7/s | 1.289 | 1.000 (1.000–1.000, n=2) | 0.962 (0.919–1.005, n=2) | 0.833 (0.590–1.076, n=2) | 0.776 (0.558–0.994, n=2) | - | 0.846 (0.720–0.972, n=2) | 0.994 (0.988–1.001, n=2) | 0.886 (0.884–0.887, n=2) |
| large-mongo@100k | head/base | calibrated (unequal load) | 1.368 | 2.002 (1.947–2.004, n=3) | 0.470 (0.049–0.486, n=3) | 0.769 (0.413–0.870, n=3) | 0.876 (0.428–0.934, n=3) | - | 0.919 (0.436–1.006, n=3) | 0.488 (0.487–0.504, n=3) | 0.664 (0.656–0.681, n=3) |
| large-mongo@100k | head/base | equal rate 622.7/s | 1.368 | 1.410 (1.324–1.495, n=2) | 0.001 (0.001–0.001, n=2) | 0.007 (0.006–0.009, n=2) | 0.015 (0.012–0.017, n=2) | - | 0.028 (0.022–0.034, n=2) | 0.634 (0.593–0.674, n=2) | 0.736 (0.729–0.742, n=2) |
| large-mongo@1k | head/base | calibrated (unequal load) | 1.503 | 1.503 (1.503–1.503, n=3) | 0.242 (0.187–0.339, n=3) | 0.735 (0.500–1.549, n=3) | 0.714 (0.657–1.739, n=3) | - | 0.622 (0.564–1.379, n=3) | 0.665 (0.665–0.673, n=3) | 0.710 (0.675–0.760, n=3) |
| large-mongo@1k | head/base | equal rate 391.0/s | 1.503 | 1.000 (1.000–1.000, n=2) | 0.056 (0.036–0.077, n=2) | 0.192 (0.074–0.310, n=2) | 0.214 (0.072–0.356, n=2) | - | 0.186 (0.067–0.304, n=2) | 0.989 (0.986–0.993, n=2) | 0.750 (0.735–0.764, n=2) |
| large-pg@100k | head/base | calibrated (unequal load) | 1.204 | 1.605 (1.564–1.605, n=3) | 18.813 (16.550–272.370, n=3) | 11.317 (5.083–28.091, n=3) | 5.830 (2.843–10.258, n=3) | - | 4.541 (2.230–7.895, n=3) | 0.660 (0.623–0.691, n=3) | 0.877 (0.851–0.932, n=3) |
| large-pg@100k | head/base | equal rate 675.7/s | 1.204 | 1.000 (1.000–1.000, n=2) | 0.334 (0.325–0.343, n=2) | 0.263 (0.145–0.380, n=2) | 0.520 (0.448–0.592, n=2) | - | 0.564 (0.561–0.568, n=2) | 0.904 (0.891–0.918, n=2) | 0.866 (0.866–0.867, n=2) |
| large-pg@1k | head/base | calibrated (unequal load) | 1.126 | 1.501 (1.501–1.501, n=3) | 18.257 (17.869–21.568, n=3) | 16.652 (5.195–21.834, n=3) | 13.673 (4.169–14.104, n=3) | - | 9.286 (2.206–11.469, n=3) | 0.826 (0.820–0.829, n=3) | 0.930 (0.925–0.938, n=3) |
| large-pg@1k | head/base | equal rate 386.1/s | 1.126 | 1.000 (1.000–1.000, n=2) | 1.014 (1.005–1.022, n=2) | 1.080 (0.525–1.635, n=2) | 0.997 (0.529–1.465, n=2) | - | 1.285 (0.953–1.616, n=2) | 1.032 (1.028–1.035, n=2) | 0.923 (0.922–0.923, n=2) |

---

### e1-mongo-uniform@100k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (31–115 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | ≥ 2222.4/s | 4096 | 1000.1/s | 45% |
| head | 3562.0/s | 256 | 1602.9/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1777.9/s | 1091.8/s | 21837 | 13721 | 12779.5 ms | 16121.9 ms | 17039.4 ms | n/a | 17825.8 ms | 0.91 cores | kafka 39% · mongo 140% | 0.92 of 1 | mongo 1.40 | 2.47 | 138.2 KB | http-429-wow.command.busy 6454 · generator-overload 5488 · exception-ConnectException 1779 |
| probe2-base | base | 1333.4/s | 1232.0/s | 24639 | 2029 | 2457.6 ms | 4325.4 ms | 4948.0 ms | n/a | 5767.2 ms | 0.98 cores | kafka 42% · mongo 142% | 0.81 of 1 | mongo 1.42 | 2.29 | 204.5 KB | http-429-wow.command.busy 2029 |
| probe3-base | base | 1000.1/s | 1000.1/s | 20002 | 0 | 456.7 ms | 1663.0 ms | 2244.6 ms | n/a | 3063.8 ms | 0.90 cores | kafka 34% · mongo 156% | 0.60 of 1 | mongo 1.56 | 2.80 | 245.7 KB | - |
| probe1-head | head | 2849.6/s | 911.3/s | 18226 | 38766 | 14811.1 ms | 15925.2 ms | 16515.1 ms | n/a | 16566.3 ms | 0.58 cores | kafka 43% · mongo 86% | 0.97 of 1 | load generator 0.97 | 2.06 | 36.6 KB | generator-overload 35521 · exception-ConnectException 3245 |
| probe2-head | head | 2137.2/s | 1861.5/s | 37231 | 5512 | 10289.2 ms | 13959.2 ms | 14286.8 ms | n/a | 14854.1 ms | 0.69 cores | kafka 45% · mongo 104% | 0.96 of 1 | mongo 1.04 | 1.17 | 82.8 KB | generator-overload 5512 |
| probe3-head | head | 1602.9/s | 1602.8/s | 32057 | 0 | 1900.5 ms | 3833.9 ms | 3899.4 ms | n/a | 5076.6 ms | 0.74 cores | kafka 45% · mongo 116% | 0.89 of 1 | mongo 1.16 | 1.47 | 112.6 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1000.1/s | 1000.1/s | 40004 | 0 | 206.8 ms | 700.4 ms | 1163.3 ms | n/a | 1777.7 ms | 0.85 cores | kafka 37% · mongo 164% | 0.41 of 1 | mongo 1.64 | 2.86 | 252.3 KB | - |
| r1-head | head | 1602.9/s | 1602.9/s | 64115 | 0 | 5.8 ms | 53.5 ms | 122.9 ms | n/a | 226.3 ms | 0.76 cores | kafka 40% · mongo 131% | 0.54 of 1 | mongo 1.31 | 1.54 | 112.9 KB | - |
| r2-head | head | 1602.9/s | 1602.9/s | 64115 | 0 | 5.2 ms | 50.4 ms | 134.1 ms | n/a | 268.3 ms | 0.76 cores | kafka 41% · mongo 128% | 0.53 of 1 | mongo 1.28 | 1.53 | 114.0 KB | - |
| r2-base | base | 1000.1/s | 1000.1/s | 40004 | 0 | 197.6 ms | 589.8 ms | 831.5 ms | n/a | 1171.5 ms | 0.87 cores | kafka 33% · mongo 167% | 0.41 of 1 | mongo 1.67 | 2.86 | 254.7 KB | - |
| r3-base | base | 1000.1/s | 1000.1/s | 40004 | 0 | 247.8 ms | 610.3 ms | 798.7 ms | n/a | 1048.6 ms | 0.86 cores | kafka 36% · mongo 164% | 0.43 of 1 | mongo 1.64 | 2.85 | 246.8 KB | - |
| r3-head | head | 1602.9/s | 1602.9/s | 64115 | 0 | 5.1 ms | 56.1 ms | 128.0 ms | n/a | 282.6 ms | 0.75 cores | kafka 39% · mongo 126% | 0.52 of 1 | mongo 1.26 | 1.50 | 115.3 KB | - |
| e1-base | base | 1000.1/s | 1000.1/s | 40004 | 0 | 278.5 ms | 716.8 ms | 991.2 ms | n/a | 1450.0 ms | 0.86 cores | kafka 39% · mongo 162% | 0.43 of 1 | mongo 1.62 | 2.87 | 241.9 KB | - |
| e1-head | head | 1000.1/s | 1000.1/s | 40004 | 0 | 2.7 ms | 14.9 ms | 62.5 ms | n/a | 133.1 ms | 0.66 cores | kafka 42% · mongo 115% | 0.35 of 1 | mongo 1.15 | 2.23 | 133.6 KB | - |
| e2-head | head | 1000.1/s | 1000.1/s | 40004 | 0 | 2.7 ms | 23.3 ms | 77.3 ms | n/a | 161.8 ms | 0.64 cores | kafka 42% · mongo 111% | 0.35 of 1 | mongo 1.11 | 2.17 | 131.7 KB | - |
| e2-base | base | 1000.1/s | 1000.1/s | 40004 | 0 | 221.2 ms | 655.4 ms | 831.5 ms | n/a | 1302.5 ms | 0.85 cores | kafka 34% · mongo 167% | 0.41 of 1 | mongo 1.67 | 2.86 | 249.0 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.603 | 0.028 | 0.076 | 0.106 | - | 0.127 | 0.540 | 0.447 |
| 2 | 1.603 | 0.027 | 0.086 | 0.161 | - | 0.229 | 0.535 | 0.448 |
| 3 | 1.603 | 0.020 | 0.092 | 0.160 | - | 0.270 | 0.525 | 0.467 |

Pairs at one rate for both arms, 1000.1/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.010 | 0.021 | 0.063 | - | 0.092 | 0.777 | 0.553 |
| 2 | 1.000 | 0.012 | 0.036 | 0.093 | - | 0.124 | 0.760 | 0.529 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +25.3% (±25.0%) |
| e1-mongo-uniform@100k | r1-head | steady | **p99 > 10× p50** | p50 5.8 ms, p99 122.9 ms |
| e1-mongo-uniform@100k | r2-head | steady | **p99 > 10× p50** | p50 5.2 ms, p99 134.1 ms |
| e1-mongo-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +82.7% (±25.0%) |
| e1-mongo-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -25.2% (±25.0%) |
| e1-mongo-uniform@100k | r3-head | steady | **p99 > 10× p50** | p50 5.1 ms, p99 128.0 ms |
| e1-mongo-uniform@100k | e1-head | steady | **p99 > 10× p50** | p50 2.7 ms, p99 62.5 ms |
| e1-mongo-uniform@100k | e2-head | steady | **p99 > 10× p50** | p50 2.7 ms, p99 77.3 ms |
| e1-mongo-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +38.0% (±25.0%) |


### e1-mongo-uniform@1k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2087.4/s | 1024 | 939.3/s | 45% |
| head | 3727.6/s | 128 | 1677.4/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1669.9/s | 859.1/s | 17182 | 16215 | 17956.9 ms | 19529.7 ms | 19922.9 ms | n/a | 20185.1 ms | 1.38 cores | kafka 63% · mongo 84% | 0.92 of 1 | server 1.38 | 3.31 | 86.1 KB | generator-overload 13223 · exception-ConnectException 2662 · http-429-wow.command.busy 330 |
| probe2-base | base | 1252.4/s | 1179.0/s | 23579 | 1468 | 2277.4 ms | 6160.4 ms | 7766.0 ms | n/a | 9764.9 ms | 1.46 cores | kafka 49% · mongo 91% | 0.80 of 1 | server 1.46 | 2.42 | 148.1 KB | http-429-wow.command.busy 1468 |
| probe3-base | base | 939.3/s | 939.3/s | 18786 | 0 | 362.5 ms | 2072.6 ms | 3604.5 ms | n/a | 5406.7 ms | 1.48 cores | kafka 46% · mongo 97% | 0.54 of 1 | server 1.48 | 3.11 | 189.3 KB | - |
| probe1-head | head | 2982.1/s | 1182.6/s | 23652 | 35990 | 12714.0 ms | 15007.7 ms | 17563.6 ms | n/a | 17956.9 ms | 1.26 cores | kafka 54% · mongo 65% | 0.96 of 1 | server 1.26 | 2.07 | 39.8 KB | generator-overload 34374 · exception-ConnectException 1616 |
| probe2-head | head | 2236.6/s | 1577.9/s | 31558 | 13174 | 9568.3 ms | 15794.2 ms | 17039.4 ms | n/a | 17301.5 ms | 1.37 cores | kafka 49% · mongo 74% | 0.96 of 1 | server 1.37 | 1.65 | 72.2 KB | generator-overload 13174 |
| probe3-head | head | 1677.4/s | 1677.4/s | 33548 | 0 | 4358.1 ms | 5210.1 ms | 6619.1 ms | n/a | 6914.0 ms | 1.46 cores | kafka 49% · mongo 78% | 0.95 of 1 | server 1.46 | 1.63 | 105.3 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 939.3/s | 939.3/s | 37572 | 0 | 48.9 ms | 344.1 ms | 614.4 ms | n/a | 1245.2 ms | 1.21 cores | kafka 38% · mongo 126% | 0.38 of 1 | mongo 1.26 | 3.03 | 212.9 KB | - |
| r1-head | head | 1677.4/s | 1677.4/s | 67096 | 0 | 12.7 ms | 1105.9 ms | 1826.8 ms | n/a | 2023.4 ms | 1.14 cores | kafka 49% · mongo 109% | 0.66 of 1 | server 1.14 | 1.62 | 110.9 KB | - |
| r2-head | head | 1677.4/s | 1677.4/s | 67096 | 0 | 19.7 ms | 544.8 ms | 864.3 ms | n/a | 1114.1 ms | 1.16 cores | kafka 49% · mongo 106% | 0.69 of 1 | server 1.16 | 1.61 | 102.5 KB | - |
| r2-base | base | 939.3/s | 939.3/s | 37572 | 0 | 79.4 ms | 536.6 ms | 925.7 ms | n/a | 1777.7 ms | 1.26 cores | kafka 38% · mongo 121% | 0.40 of 1 | server 1.26 | 3.03 | 203.0 KB | - |
| r3-base | base | 939.3/s | 939.3/s | 37572 | 0 | 26.9 ms | 243.7 ms | 460.8 ms | n/a | 925.7 ms | 1.07 cores | kafka 40% · mongo 130% | 0.38 of 1 | mongo 1.30 | 2.95 | 215.9 KB | - |
| r3-head | head | 1677.4/s | 1677.4/s | 67096 | 0 | 7.3 ms | 150.5 ms | 337.9 ms | n/a | 536.6 ms | 1.05 cores | kafka 47% · mongo 112% | 0.60 of 1 | mongo 1.12 | 1.57 | 111.3 KB | - |
| e1-base | base | 939.3/s | 939.3/s | 37572 | 0 | 50.4 ms | 342.0 ms | 581.6 ms | n/a | 1179.6 ms | 1.18 cores | kafka 37% · mongo 126% | 0.37 of 1 | mongo 1.26 | 2.99 | 209.5 KB | - |
| e1-head | head | 939.3/s | 939.3/s | 37572 | 0 | 2.9 ms | 35.3 ms | 126.5 ms | n/a | 234.5 ms | 0.85 cores | kafka 44% · mongo 97% | 0.35 of 1 | mongo 0.97 | 2.42 | 130.7 KB | - |
| e2-head | head | 939.3/s | 939.3/s | 37572 | 0 | 3.5 ms | 44.0 ms | 153.6 ms | n/a | 376.8 ms | 0.88 cores | kafka 44% · mongo 95% | 0.34 of 1 | mongo 0.95 | 2.42 | 129.1 KB | - |
| e2-base | base | 939.3/s | 939.3/s | 37572 | 0 | 46.3 ms | 536.6 ms | 1003.5 ms | n/a | 1630.2 ms | 1.22 cores | kafka 38% · mongo 126% | 0.38 of 1 | mongo 1.26 | 3.05 | 208.8 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.786 | 0.259 | 3.214 | 2.973 | - | 1.625 | 0.533 | 0.521 |
| 2 | 1.786 | 0.248 | 1.015 | 0.934 | - | 0.627 | 0.532 | 0.505 |
| 3 | 1.786 | 0.273 | 0.618 | 0.733 | - | 0.580 | 0.533 | 0.516 |

Pairs at one rate for both arms, 939.3/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.057 | 0.103 | 0.217 | - | 0.199 | 0.808 | 0.624 |
| 2 | 1.000 | 0.075 | 0.082 | 0.153 | - | 0.231 | 0.796 | 0.618 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -75.7% (±25.0%) |
| e1-mongo-uniform@1k | r1-base | steady | **p99 > 10× p50** | p50 48.9 ms, p99 614.4 ms |
| e1-mongo-uniform@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.1% (±25.0%) |
| e1-mongo-uniform@1k | r1-head | steady | **p99 > 10× p50** | p50 12.7 ms, p99 1826.8 ms |
| e1-mongo-uniform@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -65.9% (±25.0%) |
| e1-mongo-uniform@1k | r2-head | steady | **p99 > 10× p50** | p50 19.7 ms, p99 864.3 ms |
| e1-mongo-uniform@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -83.8% (±25.0%) |
| e1-mongo-uniform@1k | r2-base | steady | **p99 > 10× p50** | p50 79.4 ms, p99 925.7 ms |
| e1-mongo-uniform@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -67.3% (±25.0%) |
| e1-mongo-uniform@1k | r3-base | steady | **p99 > 10× p50** | p50 26.9 ms, p99 460.8 ms |
| e1-mongo-uniform@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -50.5% (±25.0%) |
| e1-mongo-uniform@1k | r3-head | steady | **p99 > 10× p50** | p50 7.3 ms, p99 337.9 ms |
| e1-mongo-uniform@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -82.7% (±25.0%) |
| e1-mongo-uniform@1k | e1-base | steady | **p99 > 10× p50** | p50 50.4 ms, p99 581.6 ms |
| e1-mongo-uniform@1k | e1-head | steady | **p99 > 10× p50** | p50 2.9 ms, p99 126.5 ms |
| e1-mongo-uniform@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.5% (±25.0%) |
| e1-mongo-uniform@1k | e2-head | steady | **p99 > 10× p50** | p50 3.5 ms, p99 153.6 ms |
| e1-mongo-uniform@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -69.6% (±25.0%) |
| e1-mongo-uniform@1k | e2-base | steady | **p99 > 10× p50** | p50 46.3 ms, p99 1003.5 ms |


### e1-mongo-zipf@100k

E1 write on MongoDB, Zipf keys (hot accounts, lane and cache contention): head against baseline.

E1, zipf keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (58–172 s), then waited until the nodes were quiet again (4–5 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 532.5/s | 512 | 426.0/s | 80% |
| head | 2122.5/s | 512 | 1273.5/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 426.0/s | 426.0/s | 8520 | 0 | 40.7 ms | 6160.4 ms | 6750.2 ms | n/a | 7130.6 ms | 0.82 cores | kafka 50% · mongo 139% | 0.56 of 1 | mongo 1.39 | 6.36 | 273.5 KB | - |
| probe1-head | head | 1698.0/s | 857.8/s | 17156 | 16804 | 17563.6 ms | 20578.3 ms | 20691.3 ms | n/a | 20691.3 ms | 0.68 cores | kafka 59% · mongo 99% | 0.96 of 1 | mongo 0.99 | 2.64 | 44.0 KB | generator-overload 10851 · exception-ConnectException 5953 |
| probe2-head | head | 1273.5/s | 1273.5/s | 25470 | 0 | 10485.8 ms | 14090.2 ms | 15007.7 ms | n/a | 15663.1 ms | 0.88 cores | kafka 51% · mongo 109% | 0.94 of 1 | mongo 1.09 | 1.95 | 88.2 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 426.0/s | 426.0/s | 17040 | 0 | 12.4 ms | 417.8 ms | 1335.3 ms | n/a | 1884.2 ms | 0.79 cores | kafka 46% · mongo 138% | 0.29 of 1 | mongo 1.38 | 6.18 | 262.4 KB | - |
| r1-head | head | 1273.5/s | 1257.8/s | 50313 | 627 | 11206.7 ms | 14483.5 ms | 14680.1 ms | n/a | 14876.7 ms | 0.88 cores | kafka 58% · mongo 110% | 0.95 of 1 | mongo 1.10 | 2.04 | 103.6 KB | generator-overload 627 |
| r2-head | head | 1273.5/s | 1267.3/s | 50690 | 250 | 11730.9 ms | 13631.5 ms | 17825.8 ms | n/a | 18087.9 ms | 0.87 cores | kafka 53% · mongo 113% | 0.94 of 1 | mongo 1.13 | 2.00 | 100.7 KB | exception-ConnectException 250 |
| r2-base | base | 426.0/s | 426.0/s | 17040 | 0 | 13.1 ms | 368.6 ms | 901.1 ms | n/a | 1261.6 ms | 0.78 cores | kafka 49% · mongo 138% | 0.30 of 1 | mongo 1.38 | 6.23 | 264.9 KB | - |
| r3-base | base | 426.0/s | 426.0/s | 17040 | 0 | 12.2 ms | 292.9 ms | 782.3 ms | n/a | 1490.9 ms | 0.78 cores | kafka 49% · mongo 140% | 0.29 of 1 | mongo 1.40 | 6.28 | 265.3 KB | - |
| r3-head | head | 1273.5/s | 1273.5/s | 50940 | 0 | 4423.7 ms | 8028.2 ms | 8257.5 ms | n/a | 8355.8 ms | 0.86 cores | kafka 55% · mongo 114% | 0.94 of 1 | mongo 1.14 | 2.01 | 104.7 KB | - |
| e1-base | base | 426.0/s | 426.0/s | 17040 | 0 | 14.5 ms | 499.7 ms | 1646.6 ms | n/a | 2277.4 ms | 0.79 cores | kafka 47% · mongo 140% | 0.30 of 1 | mongo 1.40 | 6.23 | 267.1 KB | - |
| e1-head | head | 426.0/s | 426.0/s | 17040 | 0 | 2.5 ms | 11.6 ms | 39.7 ms | n/a | 105.0 ms | 0.54 cores | kafka 58% · mongo 106% | 0.27 of 1 | mongo 1.06 | 5.12 | 146.6 KB | - |
| e2-head | head | 426.0/s | 426.0/s | 17040 | 0 | 2.7 ms | 15.1 ms | 42.8 ms | n/a | 143.4 ms | 0.55 cores | kafka 59% · mongo 105% | 0.27 of 1 | mongo 1.05 | 5.14 | 145.7 KB | - |
| e2-base | base | 426.0/s | 426.0/s | 17040 | 0 | 17.2 ms | 602.1 ms | 3145.7 ms | n/a | 4060.0 ms | 0.81 cores | kafka 47% · mongo 143% | 0.30 of 1 | mongo 1.43 | 6.35 | 263.8 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 2.953 | 902.671 | 34.667 | 10.994 | - | 7.896 | 0.330 | 0.395 |
| 2 | 2.975 | 894.195 | 36.978 | 19.782 | - | 14.338 | 0.321 | 0.380 |
| 3 | 2.989 | 363.819 | 27.413 | 10.555 | - | 5.604 | 0.319 | 0.395 |

Pairs at one rate for both arms, 426.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.175 | 0.023 | 0.024 | - | 0.046 | 0.822 | 0.549 |
| 2 | 1.000 | 0.159 | 0.025 | 0.014 | - | 0.035 | 0.809 | 0.552 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-zipf@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -30.8% (±25.0%) |
| e1-mongo-zipf@100k | r1-base | steady | **p99 > 10× p50** | p50 12.4 ms, p99 1335.3 ms |
| e1-mongo-zipf@100k | r1-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r1-head | steady | **load generator saturated** | 0.95 of 1 cores: the run measured the generator |
| e1-mongo-zipf@100k | r1-head | steady | **error spike** | 627 of 50940 failed: generator-overload 627 |
| e1-mongo-zipf@100k | r2-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@100k | r2-head | steady | **load generator saturated** | 0.94 of 1 cores: the run measured the generator |
| e1-mongo-zipf@100k | r2-base | steady | **p99 > 10× p50** | p50 13.1 ms, p99 901.1 ms |
| e1-mongo-zipf@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -43.0% (±25.0%) |
| e1-mongo-zipf@100k | r3-base | steady | **p99 > 10× p50** | p50 12.2 ms, p99 782.3 ms |
| e1-mongo-zipf@100k | r3-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r3-head | steady | **load generator saturated** | 0.94 of 1 cores: the run measured the generator |
| e1-mongo-zipf@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -57.3% (±25.0%) |
| e1-mongo-zipf@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -56.7% (±25.0%) |
| e1-mongo-zipf@100k | e1-base | steady | **p99 > 10× p50** | p50 14.5 ms, p99 1646.6 ms |
| e1-mongo-zipf@100k | e2-base | steady | **p99 > 10× p50** | p50 17.2 ms, p99 3145.7 ms |


### e1-mongo-zipf@1k

E1 write on MongoDB, Zipf keys (hot accounts, lane and cache contention): head against baseline.

E1, zipf keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 441.0/s | 128 | 352.8/s | 80% |
| head | 3029.3/s | 512 | 1363.2/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 352.8/s | 352.3/s | 7046 | 10 | 47.6 ms | 8355.8 ms | 9830.4 ms | n/a | 10009.3 ms | 1.40 cores | kafka 53% · mongo 88% | 0.37 of 1 | server 1.40 | 7.99 | 275.4 KB | http-504-wow.wait.timeout 10 |
| probe1-head | head | 2423.4/s | 870.6/s | 17413 | 31055 | 12910.6 ms | 16449.5 ms | 17039.4 ms | n/a | 17553.5 ms | 1.34 cores | kafka 67% · mongo 57% | 0.97 of 1 | server 1.34 | 2.97 | 32.8 KB | generator-overload 27497 · exception-ConnectException 3501 · http-429-wow.command.busy 57 |
| probe2-head | head | 1817.6/s | 1093.3/s | 21867 | 14485 | 14876.7 ms | 19005.4 ms | 21102.6 ms | n/a | 22544.4 ms | 1.48 cores | kafka 67% · mongo 57% | 0.96 of 1 | server 1.48 | 2.48 | 53.5 KB | generator-overload 10772 · exception-ConnectException 3713 |
| probe3-head | head | 1363.2/s | 1363.2/s | 27263 | 0 | 7897.1 ms | 10616.8 ms | 11730.9 ms | n/a | 12386.3 ms | 1.53 cores | kafka 66% · mongo 65% | 0.95 of 1 | server 1.53 | 2.09 | 89.5 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 352.8/s | 352.8/s | 14112 | 0 | 6.0 ms | 1744.9 ms | 6553.6 ms | n/a | 8257.5 ms | 0.81 cores | kafka 53% · mongo 89% | 0.20 of 1 | mongo 0.89 | 6.33 | 261.3 KB | - |
| r1-head | head | 1363.2/s | 1363.2/s | 54527 | 0 | 31.2 ms | 294.9 ms | 471.0 ms | n/a | 794.6 ms | 1.25 cores | kafka 48% · mongo 88% | 0.60 of 1 | server 1.25 | 1.92 | 104.3 KB | - |
| r2-head | head | 1363.2/s | 1363.2/s | 54527 | 0 | 26.2 ms | 1458.2 ms | 2736.1 ms | n/a | 3342.3 ms | 1.33 cores | kafka 49% · mongo 93% | 0.67 of 1 | server 1.33 | 2.02 | 109.5 KB | - |
| r2-base | base | 352.8/s | 352.8/s | 14112 | 0 | 7.7 ms | 786.4 ms | 2785.3 ms | n/a | 3326.0 ms | 0.82 cores | kafka 50% · mongo 88% | 0.21 of 1 | mongo 0.88 | 6.25 | 255.0 KB | - |
| r3-base | base | 352.8/s | 352.8/s | 14112 | 0 | 6.0 ms | 544.8 ms | 4882.4 ms | n/a | 5852.4 ms | 0.74 cores | kafka 49% · mongo 87% | 0.18 of 1 | mongo 0.87 | 5.94 | 258.2 KB | - |
| r3-head | head | 1363.2/s | 1363.2/s | 54527 | 0 | 31.4 ms | 540.7 ms | 1171.5 ms | n/a | 2015.2 ms | 1.23 cores | kafka 50% · mongo 90% | 0.63 of 1 | server 1.23 | 1.94 | 104.8 KB | - |
| e1-base | base | 352.8/s | 352.8/s | 14112 | 0 | 6.6 ms | 2834.4 ms | 7536.6 ms | n/a | 8192.0 ms | 0.71 cores | kafka 51% · mongo 87% | 0.20 of 1 | mongo 0.87 | 5.93 | 259.3 KB | - |
| e1-head | head | 352.8/s | 352.8/s | 14112 | 0 | 2.3 ms | 87.6 ms | 183.3 ms | n/a | 294.9 ms | 0.50 cores | kafka 53% · mongo 61% | 0.20 of 1 | mongo 0.61 | 4.65 | 143.1 KB | - |
| e2-head | head | 352.8/s | 352.8/s | 14112 | 0 | 2.8 ms | 156.7 ms | 288.8 ms | n/a | 428.0 ms | 0.44 cores | kafka 51% · mongo 58% | 0.17 of 1 | mongo 0.58 | 4.34 | 136.5 KB | - |
| e2-base | base | 352.8/s | 352.8/s | 14112 | 0 | 20.1 ms | 1327.1 ms | 4718.6 ms | n/a | 6422.5 ms | 0.72 cores | kafka 46% · mongo 84% | 0.18 of 1 | mongo 0.84 | 5.74 | 252.7 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 3.864 | 5.248 | 0.169 | 0.072 | - | 0.096 | 0.303 | 0.399 |
| 2 | 3.864 | 3.417 | 1.854 | 0.982 | - | 1.005 | 0.324 | 0.429 |
| 3 | 3.864 | 5.270 | 0.992 | 0.240 | - | 0.344 | 0.326 | 0.406 |

Pairs at one rate for both arms, 352.8/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.354 | 0.031 | 0.024 | - | 0.036 | 0.783 | 0.552 |
| 2 | 1.000 | 0.140 | 0.118 | 0.061 | - | 0.067 | 0.756 | 0.540 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-zipf@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -68.2% (±25.0%) |
| e1-mongo-zipf@1k | r1-base | steady | **p99 > 10× p50** | p50 6.0 ms, p99 6553.6 ms |
| e1-mongo-zipf@1k | r1-head | steady | **tail read the store** | overflow=5 |
| e1-mongo-zipf@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -33.3% (±25.0%) |
| e1-mongo-zipf@1k | r1-head | steady | **p99 > 10× p50** | p50 31.2 ms, p99 471.0 ms |
| e1-mongo-zipf@1k | r2-head | steady | **tail read the store** | overflow=4 |
| e1-mongo-zipf@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -71.4% (±25.0%) |
| e1-mongo-zipf@1k | r2-head | steady | **p99 > 10× p50** | p50 26.2 ms, p99 2736.1 ms |
| e1-mongo-zipf@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -69.9% (±25.0%) |
| e1-mongo-zipf@1k | r2-base | steady | **p99 > 10× p50** | p50 7.7 ms, p99 2785.3 ms |
| e1-mongo-zipf@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -48.2% (±25.0%) |
| e1-mongo-zipf@1k | r3-base | steady | **p99 > 10× p50** | p50 6.0 ms, p99 4882.4 ms |
| e1-mongo-zipf@1k | r3-head | steady | **tail read the store** | overflow=4 |
| e1-mongo-zipf@1k | r3-head | steady | **p99 > 10× p50** | p50 31.4 ms, p99 1171.5 ms |
| e1-mongo-zipf@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -86.8% (±25.0%) |
| e1-mongo-zipf@1k | e1-base | steady | **p99 > 10× p50** | p50 6.6 ms, p99 7536.6 ms |
| e1-mongo-zipf@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -34.6% (±25.0%) |
| e1-mongo-zipf@1k | e1-head | steady | **p99 > 10× p50** | p50 2.3 ms, p99 183.3 ms |
| e1-mongo-zipf@1k | e2-head | steady | **p99 > 10× p50** | p50 2.8 ms, p99 288.8 ms |
| e1-mongo-zipf@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +451.0% (±25.0%) |
| e1-mongo-zipf@1k | e2-base | steady | **p99 > 10× p50** | p50 20.1 ms, p99 4718.6 ms |
| e1-mongo-zipf@1k | probe2-head | overload (probe) | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | probe3-head | overload (probe) | **tail read the store** | overflow=1 |


### e1-pg-uniform@100k

E1 write on PostgreSQL: head against baseline.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (30–47 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2487.0/s | 32 | 1989.6/s | 80% |
| head | 4361.1/s | 128 | 1962.5/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1989.6/s | 1989.6/s | 39792 | 0 | 4325.4 ms | 5668.9 ms | 7471.1 ms | n/a | 7536.6 ms | 0.87 cores | kafka 41% · postgres 90% | 0.93 of 1 | load generator 0.93 | 1.09 | 121.0 KB | - |
| probe1-head | head | 3488.9/s | 1588.0/s | 31761 | 38016 | 9633.8 ms | 14024.7 ms | 14155.8 ms | n/a | 14286.8 ms | 0.66 cores | kafka 44% · postgres 47% | 0.97 of 1 | load generator 0.97 | 0.99 | 38.2 KB | generator-overload 38016 |
| probe2-head | head | 2616.7/s | 2026.2/s | 40524 | 11810 | 8454.1 ms | 10551.3 ms | 13369.3 ms | n/a | 13854.3 ms | 0.70 cores | kafka 41% · postgres 55% | 0.97 of 1 | load generator 0.97 | 0.82 | 66.2 KB | generator-overload 11810 |
| probe3-head | head | 1962.5/s | 1962.5/s | 39250 | 0 | 5.8 ms | 2261.0 ms | 2342.9 ms | n/a | 2355.9 ms | 0.90 cores | kafka 41% · postgres 69% | 0.78 of 1 | server 0.90 | 1.02 | 90.9 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1989.6/s | 1989.6/s | 79584 | 0 | 20.7 ms | 1728.5 ms | 2089.0 ms | n/a | 2211.8 ms | 0.95 cores | kafka 38% · postgres 96% | 0.76 of 1 | postgres 0.96 | 1.15 | 120.6 KB | - |
| r1-head | head | 1962.5/s | 1962.5/s | 78500 | 0 | 2.4 ms | 31.2 ms | 110.6 ms | n/a | 327.7 ms | 0.95 cores | kafka 46% · postgres 79% | 0.65 of 1 | server 0.95 | 1.12 | 86.7 KB | - |
| r2-head | head | 1962.5/s | 1962.5/s | 78500 | 0 | 3.5 ms | 63.5 ms | 166.9 ms | n/a | 342.0 ms | 0.93 cores | kafka 45% · postgres 79% | 0.66 of 1 | server 0.93 | 1.11 | 86.3 KB | - |
| r2-base | base | 1989.6/s | 1989.6/s | 79584 | 0 | 1499.1 ms | 3751.9 ms | 4259.8 ms | n/a | 4456.4 ms | 0.93 cores | kafka 43% · postgres 97% | 0.90 of 1 | postgres 0.97 | 1.17 | 119.6 KB | - |
| r3-base | base | 1989.6/s | 1989.6/s | 79584 | 0 | 172.0 ms | 2965.5 ms | 3358.7 ms | n/a | 3501.2 ms | 0.96 cores | kafka 41% · postgres 97% | 0.83 of 1 | postgres 0.97 | 1.18 | 119.1 KB | - |
| r3-head | head | 1962.5/s | 1962.5/s | 78500 | 0 | 2.6 ms | 43.8 ms | 140.3 ms | n/a | 261.1 ms | 0.96 cores | kafka 45% · postgres 79% | 0.65 of 1 | server 0.96 | 1.12 | 86.8 KB | - |
| e1-base | base | 1962.5/s | 1962.5/s | 78500 | 0 | 72.7 ms | 2326.5 ms | 2424.8 ms | n/a | 2523.1 ms | 0.97 cores | kafka 39% · postgres 99% | 0.78 of 1 | postgres 0.99 | 1.20 | 120.6 KB | - |
| e1-head | head | 1962.5/s | 1962.5/s | 78500 | 0 | 2.4 ms | 43.0 ms | 149.5 ms | n/a | 297.0 ms | 0.95 cores | kafka 45% · postgres 78% | 0.64 of 1 | server 0.95 | 1.11 | 86.5 KB | - |
| e2-head | head | 1962.5/s | 1962.5/s | 78500 | 0 | 1.5 ms | 18.6 ms | 106.5 ms | n/a | 311.3 ms | 0.97 cores | kafka 39% · postgres 76% | 0.60 of 1 | server 0.97 | 1.08 | 86.2 KB | - |
| e2-base | base | 1962.5/s | 1962.5/s | 78500 | 0 | 426.0 ms | 1941.5 ms | 2211.8 ms | n/a | 2457.6 ms | 0.96 cores | kafka 46% · postgres 103% | 0.85 of 1 | postgres 1.03 | 1.25 | 117.2 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.986 | 0.118 | 0.018 | 0.053 | - | 0.148 | 0.976 | 0.719 |
| 2 | 0.986 | 0.002 | 0.017 | 0.039 | - | 0.077 | 0.944 | 0.721 |
| 3 | 0.986 | 0.015 | 0.015 | 0.042 | - | 0.075 | 0.953 | 0.729 |

Pairs at one rate for both arms, 1962.5/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.033 | 0.018 | 0.062 | - | 0.118 | 0.930 | 0.717 |
| 2 | 1.000 | 0.003 | 0.010 | 0.048 | - | 0.127 | 0.864 | 0.736 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-pg-uniform@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -98.4% (±25.0%) |
| e1-pg-uniform@100k | r1-base | steady | **p99 > 10× p50** | p50 20.7 ms, p99 2089.0 ms |
| e1-pg-uniform@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -39.8% (±25.0%) |
| e1-pg-uniform@100k | r1-head | steady | **p99 > 10× p50** | p50 2.4 ms, p99 110.6 ms |
| e1-pg-uniform@100k | r2-head | steady | **p99 > 10× p50** | p50 3.5 ms, p99 166.9 ms |
| e1-pg-uniform@100k | r2-base | steady | **load generator saturated** | 0.90 of 1 cores: the run measured the generator |
| e1-pg-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -90.4% (±25.0%) |
| e1-pg-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -99.5% (±25.0%) |
| e1-pg-uniform@100k | r3-base | steady | **p99 > 10× p50** | p50 172.0 ms, p99 3358.7 ms |
| e1-pg-uniform@100k | r3-head | steady | **p99 > 10× p50** | p50 2.6 ms, p99 140.3 ms |
| e1-pg-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -99.1% (±25.0%) |
| e1-pg-uniform@100k | e1-base | steady | **p99 > 10× p50** | p50 72.7 ms, p99 2424.8 ms |
| e1-pg-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.5% (±25.0%) |
| e1-pg-uniform@100k | e1-head | steady | **p99 > 10× p50** | p50 2.4 ms, p99 149.5 ms |
| e1-pg-uniform@100k | e2-head | steady | **p99 > 10× p50** | p50 1.5 ms, p99 106.5 ms |
| e1-pg-uniform@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -89.6% (±25.0%) |


### e1-pg-uniform@1k

E1 write on PostgreSQL: head against baseline.

E1, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2087.4/s | 128 | 704.5/s | 34% (**not sustained**) |
| head | 2358.3/s | 256 | 1061.2/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1669.9/s | 879.8/s | 17596 | 15801 | 16318.5 ms | 21626.9 ms | 21626.9 ms | n/a | 21889.0 ms | 1.50 cores | kafka 78% · postgres 43% | 0.93 of 1 | server 1.50 | 3.09 | 44.9 KB | generator-overload 12545 · exception-ConnectException 3256 |
| probe2-base | base | 1252.4/s | 778.6/s | 15573 | 9474 | 18743.3 ms | 23724.0 ms | 25296.9 ms | n/a | 26607.6 ms | 1.61 cores | kafka 81% · postgres 42% | 0.89 of 1 | server 1.61 | 3.64 | 64.4 KB | generator-overload 4616 · exception-ConnectException 3112 · http-429-wow.command.busy 1732 · http-504-wow.wait.timeout 14 |
| probe3-base | base | 939.3/s | 500.9/s | 10018 | 8768 | 10289.2 ms | 14745.6 ms | 15859.7 ms | n/a | 16711.7 ms | 1.63 cores | kafka 82% · postgres 38% | 0.92 of 1 | server 1.63 | 5.64 | 83.2 KB | http-429-wow.command.busy 7301 · http-504-wow.wait.timeout 1467 |
| probe4-base | base | 704.5/s | 692.9/s | 13857 | 233 | 3113.0 ms | 8912.9 ms | 10616.8 ms | n/a | 11468.8 ms | 1.66 cores | kafka 74% · postgres 45% | 0.86 of 1 | server 1.66 | 4.12 | 123.3 KB | http-504-wow.wait.timeout 233 |
| probe1-head | head | 1886.6/s | 689.4/s | 13787 | 23945 | 19136.5 ms | 21233.7 ms | 21364.7 ms | n/a | 21408.3 ms | 1.49 cores | kafka 81% · postgres 39% | 0.94 of 1 | server 1.49 | 3.89 | 29.7 KB | generator-overload 16971 · exception-ConnectException 6974 |
| probe2-head | head | 1415.0/s | 883.8/s | 17676 | 10624 | 17694.7 ms | 19267.6 ms | 22282.2 ms | n/a | 22675.5 ms | 1.56 cores | kafka 85% · postgres 39% | 0.94 of 1 | server 1.56 | 3.16 | 55.2 KB | generator-overload 5388 · exception-ConnectException 5236 |
| probe3-head | head | 1061.2/s | 1061.2/s | 21224 | 0 | 8912.9 ms | 9830.4 ms | 10747.9 ms | n/a | 12255.2 ms | 1.75 cores | kafka 84% · postgres 35% | 0.94 of 1 | server 1.75 | 2.77 | 91.3 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 704.5/s | 704.5/s | 28180 | 0 | 10.3 ms | 1630.2 ms | 4423.7 ms | n/a | 7503.9 ms | 1.61 cores | kafka 53% · postgres 60% | 0.48 of 1 | server 1.61 | 3.90 | 127.5 KB | - |
| r1-head | head | 1061.2/s | 1061.2/s | 42448 | 0 | 321.5 ms | 6193.2 ms | 7241.7 ms | n/a | 7419.3 ms | 1.58 cores | kafka 57% · postgres 61% | 0.80 of 1 | server 1.58 | 2.60 | 95.1 KB | - |
| r2-head | head | 1061.2/s | 1061.2/s | 42448 | 0 | 1728.5 ms | 8388.6 ms | 9044.0 ms | n/a | 9214.9 ms | 1.66 cores | kafka 60% · postgres 58% | 0.82 of 1 | server 1.66 | 2.68 | 99.8 KB | - |
| r2-base | base | 704.5/s | 704.5/s | 28180 | 0 | 4.1 ms | 125.4 ms | 231.4 ms | n/a | 415.7 ms | 1.51 cores | kafka 59% · postgres 55% | 0.43 of 1 | server 1.51 | 3.76 | 109.5 KB | - |
| r3-base | base | 704.5/s | 665.8/s | 26630 | 1550 | 1130.5 ms | 8585.2 ms | 9895.9 ms | n/a | 11468.8 ms | 1.66 cores | kafka 47% · postgres 74% | 0.58 of 1 | server 1.66 | 4.31 | 166.3 KB | http-504-wow.wait.timeout 788 · http-429-wow.command.busy 762 |
| r3-head | head | 1061.2/s | 1061.2/s | 42448 | 0 | 139.3 ms | 6062.1 ms | 6750.2 ms | n/a | 6913.4 ms | 1.61 cores | kafka 59% · postgres 61% | 0.76 of 1 | server 1.61 | 2.64 | 97.7 KB | - |
| e1-base | base | 704.5/s | 704.5/s | 28180 | 0 | 4.0 ms | 74.2 ms | 133.1 ms | n/a | 261.1 ms | 1.50 cores | kafka 57% · postgres 55% | 0.42 of 1 | server 1.50 | 3.73 | 110.7 KB | - |
| e1-head | head | 704.5/s | 704.5/s | 28180 | 0 | 3.4 ms | 47.4 ms | 245.8 ms | n/a | 626.7 ms | 1.46 cores | kafka 58% · postgres 54% | 0.39 of 1 | server 1.46 | 3.67 | 93.3 KB | - |
| e2-head | head | 704.5/s | 704.5/s | 28180 | 0 | 5.0 ms | 66.6 ms | 181.2 ms | n/a | 407.6 ms | 1.60 cores | kafka 54% · postgres 56% | 0.40 of 1 | server 1.60 | 3.83 | 94.0 KB | - |
| e2-base | base | 704.5/s | 704.5/s | 28180 | 0 | 6.4 ms | 360.4 ms | 909.3 ms | n/a | 3473.4 ms | 1.61 cores | kafka 59% · postgres 57% | 0.44 of 1 | server 1.61 | 3.94 | 117.4 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.506 | 31.208 | 3.799 | 1.637 | - | 0.989 | 0.667 | 0.745 |
| 2 | 1.506 | 418.830 | 66.874 | 39.080 | - | 22.165 | 0.713 | 0.911 |
| 3 | 1.594 | 0.123 | 0.706 | 0.682 | - | 0.603 | 0.613 | 0.588 |

Pairs at one rate for both arms, 704.5/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.841 | 0.638 | 1.846 | - | 2.400 | 0.985 | 0.843 |
| 2 | 1.000 | 0.789 | 0.185 | 0.199 | - | 0.117 | 0.974 | 0.801 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-pg-uniform@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -93.9% (±25.0%) |
| e1-pg-uniform@1k | r1-base | steady | **p99 > 10× p50** | p50 10.3 ms, p99 4423.7 ms |
| e1-pg-uniform@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -99.0% (±25.0%) |
| e1-pg-uniform@1k | r1-head | steady | **p99 > 10× p50** | p50 321.5 ms, p99 7241.7 ms |
| e1-pg-uniform@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -98.2% (±25.0%) |
| e1-pg-uniform@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -52.3% (±25.0%) |
| e1-pg-uniform@1k | r2-base | steady | **p99 > 10× p50** | p50 4.1 ms, p99 231.4 ms |
| e1-pg-uniform@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: throughput +12.4% (±5.0%), p50 -82.2% (±25.0%) |
| e1-pg-uniform@1k | r3-base | steady | **error spike** | 1550 of 28180 failed: http-504-wow.wait.timeout 788 · http-429-wow.command.busy 762 |
| e1-pg-uniform@1k | r3-base | steady | **throughput dip** | 1 s below half the rate (lowest 295/s) |
| e1-pg-uniform@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -99.1% (±25.0%) |
| e1-pg-uniform@1k | r3-head | steady | **p99 > 10× p50** | p50 139.3 ms, p99 6750.2 ms |
| e1-pg-uniform@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -38.6% (±25.0%) |
| e1-pg-uniform@1k | e1-base | steady | **p99 > 10× p50** | p50 4.0 ms, p99 133.1 ms |
| e1-pg-uniform@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.4% (±25.0%) |
| e1-pg-uniform@1k | e1-head | steady | **p99 > 10× p50** | p50 3.4 ms, p99 245.8 ms |
| e1-pg-uniform@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -56.5% (±25.0%) |
| e1-pg-uniform@1k | e2-head | steady | **p99 > 10× p50** | p50 5.0 ms, p99 181.2 ms |
| e1-pg-uniform@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -63.1% (±25.0%) |
| e1-pg-uniform@1k | e2-base | steady | **p99 > 10× p50** | p50 6.4 ms, p99 909.3 ms |
| e1-pg-uniform@1k | probe1-base | overload (probe) | **tail read the store** | gap=5 |
| e1-pg-uniform@1k | probe2-base | overload (probe) | **tail read the store** | gap=10 |
| e1-pg-uniform@1k | probe3-base | overload (probe) | **tail read the store** | gap=10 |
| e1-pg-uniform@1k | probe4-base | overload (probe) | **tail read the store** | gap=2 |


### e2-mongo@100k

E2 read-your-write on MongoDB: head against baseline.

E2, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (40–120 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 987.3/s | 2048 | 789.8/s | 80% |
| head | 1496.4/s | 256 | 897.8/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 789.8/s | 789.8/s | 15796 | 0 | 8126.5 ms | 10682.4 ms | 12189.7 ms | n/a | 13107.2 ms | 0.93 cores | kafka 40% · mongo 140% | 0.91 of 1 | mongo 1.40 | 3.46 | 287.8 KB | - |
| probe1-head | head | 1197.1/s | 1069.9/s | 21398 | 2544 | 18874.4 ms | 22413.3 ms | 24510.5 ms | n/a | 24722.6 ms | 0.75 cores | kafka 49% · mongo 101% | 0.96 of 1 | mongo 1.01 | 2.11 | 123.6 KB | generator-overload 2544 |
| probe2-head | head | 897.8/s | 897.8/s | 17956 | 0 | 6258.7 ms | 8781.8 ms | 9437.2 ms | n/a | 9830.4 ms | 0.79 cores | kafka 40% · mongo 105% | 0.96 of 1 | mongo 1.05 | 2.49 | 183.2 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 789.8/s | 789.8/s | 31592 | 0 | 1490.9 ms | 4489.2 ms | 5701.6 ms | n/a | 6979.6 ms | 0.95 cores | kafka 38% · mongo 148% | 0.72 of 1 | mongo 1.48 | 3.55 | 306.6 KB | - |
| r1-head | head | 897.8/s | 897.8/s | 35912 | 0 | 28.4 ms | 1015.8 ms | 1556.5 ms | n/a | 1703.9 ms | 0.86 cores | kafka 45% · mongo 122% | 0.73 of 1 | mongo 1.22 | 2.82 | 191.4 KB | - |
| r2-head | head | 897.8/s | 897.8/s | 35912 | 0 | 25.7 ms | 1130.5 ms | 1581.1 ms | n/a | 1720.3 ms | 0.84 cores | kafka 43% · mongo 121% | 0.71 of 1 | mongo 1.21 | 2.77 | 192.6 KB | - |
| r2-base | base | 789.8/s | 789.8/s | 31592 | 0 | 2539.5 ms | 6455.3 ms | 7569.4 ms | n/a | 9371.6 ms | 1.02 cores | kafka 38% · mongo 143% | 0.71 of 1 | mongo 1.43 | 3.58 | 308.5 KB | - |
| r3-base | base | 789.8/s | 789.8/s | 31592 | 0 | 1876.0 ms | 3883.0 ms | 4653.1 ms | n/a | 6062.1 ms | 0.96 cores | kafka 39% · mongo 145% | 0.73 of 1 | mongo 1.45 | 3.53 | 305.2 KB | - |
| r3-head | head | 897.8/s | 897.8/s | 35912 | 0 | 20.6 ms | 856.1 ms | 1359.9 ms | n/a | 1581.1 ms | 0.84 cores | kafka 42% · mongo 123% | 0.68 of 1 | mongo 1.23 | 2.77 | 192.5 KB | - |
| e1-base | base | 789.8/s | 789.8/s | 31592 | 0 | 1531.9 ms | 3555.3 ms | 4423.7 ms | n/a | 5931.0 ms | 0.93 cores | kafka 40% · mongo 146% | 0.68 of 1 | mongo 1.46 | 3.52 | 303.3 KB | - |
| e1-head | head | 789.8/s | 789.8/s | 31592 | 0 | 12.7 ms | 75.8 ms | 138.2 ms | n/a | 259.1 ms | 0.81 cores | kafka 45% · mongo 119% | 0.57 of 1 | mongo 1.19 | 3.10 | 198.9 KB | - |
| e2-head | head | 789.8/s | 789.8/s | 31592 | 0 | 14.1 ms | 90.6 ms | 152.6 ms | n/a | 315.4 ms | 0.79 cores | kafka 42% · mongo 121% | 0.59 of 1 | mongo 1.21 | 3.07 | 194.3 KB | - |
| e2-base | base | 789.8/s | 789.8/s | 31592 | 0 | 2900.0 ms | 5472.3 ms | 6553.6 ms | n/a | 7995.4 ms | 0.94 cores | kafka 40% · mongo 144% | 0.74 of 1 | mongo 1.44 | 3.51 | 308.4 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.137 | 0.019 | 0.226 | 0.273 | - | 0.244 | 0.795 | 0.624 |
| 2 | 1.137 | 0.010 | 0.175 | 0.209 | - | 0.184 | 0.774 | 0.625 |
| 3 | 1.137 | 0.011 | 0.220 | 0.292 | - | 0.261 | 0.783 | 0.631 |

Pairs at one rate for both arms, 789.8/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.008 | 0.021 | 0.031 | - | 0.044 | 0.881 | 0.656 |
| 2 | 1.000 | 0.005 | 0.017 | 0.023 | - | 0.039 | 0.874 | 0.630 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e2-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -60.2% (±25.0%) |
| e2-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -31.0% (±25.0%) |
| e2-mongo@100k | r1-head | steady | **p99 > 10× p50** | p50 28.4 ms, p99 1556.5 ms |
| e2-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -52.4% (±25.0%) |
| e2-mongo@100k | r2-head | steady | **p99 > 10× p50** | p50 25.7 ms, p99 1581.1 ms |
| e2-mongo@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -69.8% (±25.0%) |
| e2-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -53.6% (±25.0%) |
| e2-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -40.7% (±25.0%) |
| e2-mongo@100k | r3-head | steady | **p99 > 10× p50** | p50 20.6 ms, p99 1359.9 ms |
| e2-mongo@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -53.4% (±25.0%) |
| e2-mongo@100k | e1-head | steady | **p99 > 10× p50** | p50 12.7 ms, p99 138.2 ms |
| e2-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 14.1 ms, p99 152.6 ms |
| e2-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -50.8% (±25.0%) |


### e2-mongo@1k

E2 read-your-write on MongoDB: head against baseline.

E2, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1314.9/s | 1024 | 591.7/s | 45% |
| head | 1717.8/s | 256 | 1030.7/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1051.9/s | 948.4/s | 18967 | 2070 | 14549.0 ms | 17956.9 ms | 19398.7 ms | n/a | 20185.1 ms | 1.44 cores | kafka 52% · mongo 85% | 0.91 of 1 | server 1.44 | 2.97 | 164.5 KB | http-429-wow.command.busy 1967 · http-504-wow.wait.timeout 103 |
| probe2-base | base | 788.9/s | 764.1/s | 15283 | 495 | 5111.8 ms | 9830.4 ms | 10944.5 ms | n/a | 11599.9 ms | 1.54 cores | kafka 47% · mongo 86% | 0.83 of 1 | server 1.54 | 3.76 | 238.1 KB | http-504-wow.wait.timeout 318 · http-429-wow.command.busy 177 |
| probe3-base | base | 591.7/s | 591.6/s | 11833 | 0 | 1761.3 ms | 4685.8 ms | 6160.4 ms | n/a | 8388.6 ms | 1.47 cores | kafka 48% · mongo 91% | 0.70 of 1 | server 1.47 | 4.83 | 283.8 KB | - |
| probe1-head | head | 1374.2/s | 842.2/s | 16844 | 10640 | 18874.4 ms | 26083.3 ms | 28180.5 ms | n/a | 28834.2 ms | 1.39 cores | kafka 50% · mongo 67% | 0.95 of 1 | server 1.39 | 3.04 | 83.6 KB | generator-overload 8580 · exception-ConnectException 2060 |
| probe2-head | head | 1030.7/s | 1030.7/s | 20614 | 0 | 15401.0 ms | 19791.9 ms | 21889.0 ms | n/a | 23199.7 ms | 1.47 cores | kafka 52% · mongo 71% | 0.94 of 1 | server 1.47 | 2.61 | 146.9 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 591.7/s | 591.7/s | 23667 | 0 | 84.0 ms | 770.0 ms | 1196.0 ms | n/a | 2056.2 ms | 1.23 cores | kafka 37% · mongo 112% | 0.42 of 1 | server 1.23 | 4.59 | 293.7 KB | - |
| r1-head | head | 1030.7/s | 907.1/s | 36283 | 4945 | 18743.3 ms | 21758.0 ms | 23068.7 ms | n/a | 25630.7 ms | 1.23 cores | kafka 45% · mongo 80% | 0.95 of 1 | server 1.23 | 2.74 | 156.4 KB | generator-overload 4945 |
| r2-head | head | 1030.7/s | 1030.7/s | 41228 | 0 | 14352.4 ms | 15663.1 ms | 15990.8 ms | n/a | 19136.5 ms | 1.20 cores | kafka 43% · mongo 88% | 0.95 of 1 | server 1.20 | 2.43 | 179.1 KB | - |
| r2-base | base | 591.7/s | 591.7/s | 23667 | 0 | 235.5 ms | 1044.5 ms | 1425.4 ms | n/a | 2277.4 ms | 1.42 cores | kafka 40% · mongo 106% | 0.44 of 1 | server 1.42 | 4.87 | 286.4 KB | - |
| r3-base | base | 591.7/s | 591.7/s | 23667 | 0 | 94.2 ms | 569.3 ms | 925.7 ms | n/a | 1949.7 ms | 1.30 cores | - | 0.42 of 1 | server 1.30 | 2.19 | 298.0 KB | - |
| r3-head | head | 1030.7/s | 1030.7/s | 41228 | 0 | 1105.9 ms | 4620.3 ms | 5177.3 ms | n/a | 5898.2 ms | 1.20 cores | kafka 44% · mongo 96% | 0.85 of 1 | server 1.20 | 2.52 | 190.9 KB | - |
| e1-base | base | 591.7/s | 591.7/s | 23667 | 0 | 84.5 ms | 1007.6 ms | 1605.6 ms | n/a | 2113.5 ms | 1.29 cores | kafka 42% · mongo 109% | 0.43 of 1 | server 1.29 | 4.74 | 299.8 KB | - |
| e1-head | head | 591.7/s | 591.7/s | 23667 | 0 | 18.0 ms | 130.0 ms | 266.2 ms | n/a | 522.2 ms | 1.25 cores | kafka 49% · mongo 92% | 0.43 of 1 | server 1.25 | 4.49 | 203.8 KB | - |
| e2-head | head | 591.7/s | 591.7/s | 23667 | 0 | 18.9 ms | 216.1 ms | 497.7 ms | n/a | 692.2 ms | 1.32 cores | kafka 46% · mongo 91% | 0.44 of 1 | server 1.32 | 4.54 | 204.0 KB | - |
| e2-base | base | 591.7/s | 591.7/s | 23667 | 0 | 356.4 ms | 1007.6 ms | 1294.3 ms | n/a | 1835.0 ms | 1.44 cores | kafka 42% · mongo 106% | 0.46 of 1 | server 1.44 | 4.93 | 284.1 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.533 | 223.222 | 28.255 | 19.288 | - | 12.465 | 0.597 | 0.532 |
| 2 | 1.742 | 60.939 | 14.996 | 11.218 | - | 8.403 | 0.500 | 0.625 |
| 3 | 1.742 | 11.739 | 8.115 | 5.593 | - | 3.025 | 1.151 | 0.641 |

Pairs at one rate for both arms, 591.7/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.214 | 0.129 | 0.166 | - | 0.247 | 0.947 | 0.680 |
| 2 | 1.000 | 0.053 | 0.214 | 0.384 | - | 0.377 | 0.920 | 0.718 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e2-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -93.0% (±25.0%) |
| e2-mongo@1k | r1-base | steady | **p99 > 10× p50** | p50 84.0 ms, p99 1196.0 ms |
| e2-mongo@1k | r1-head | steady | **load generator saturated** | 0.95 of 1 cores: the run measured the generator |
| e2-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: throughput +31.6% (±5.0%) |
| e2-mongo@1k | r1-head | steady | **error spike** | 4945 of 41228 failed: generator-overload 4945 |
| e2-mongo@1k | r1-head | steady | **throughput dip** | 4 s below half the rate (lowest 1/s) |
| e2-mongo@1k | r2-head | steady | **load generator saturated** | 0.95 of 1 cores: the run measured the generator |
| e2-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -63.0% (±25.0%) |
| e2-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -75.4% (±25.0%) |
| e2-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -96.7% (±25.0%) |
| e2-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -86.7% (±25.0%) |
| e2-mongo@1k | e1-base | steady | **p99 > 10× p50** | p50 84.5 ms, p99 1605.6 ms |
| e2-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -68.6% (±25.0%) |
| e2-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 18.0 ms, p99 266.2 ms |
| e2-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -63.8% (±25.0%) |
| e2-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 18.9 ms, p99 497.7 ms |
| e2-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -41.6% (±25.0%) |


### e3-mongo@100k

E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (56–163 s), then waited until the nodes were quiet again (4–7 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 518.0/s | 2048 | 310.8/s | 60% |
| head | 976.8/s | 2048 | 586.1/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 414.4/s | 386.4/s | 7729 | 559 | 6783.0 ms | 9437.2 ms | 10027.0 ms | n/a | 10289.2 ms | 1.28 cores | kafka 37% · mongo 130% | 0.52 of 1 | mongo 1.30 | 7.61 | 425.4 KB | http-504-wow.wait.timeout 407 · http-429-wow.command.busy 152 |
| probe2-base | base | 310.8/s | 310.8/s | 6216 | 0 | 1138.7 ms | 2179.1 ms | 2654.2 ms | n/a | 2981.9 ms | 0.95 cores | kafka 46% · mongo 151% | 0.43 of 1 | mongo 1.51 | 9.38 | 572.4 KB | - |
| probe1-head | head | 781.4/s | 770.6/s | 15413 | 215 | 3538.9 ms | 4620.3 ms | 5111.8 ms | n/a | 5406.7 ms | 1.20 cores | kafka 49% · mongo 122% | 0.86 of 1 | mongo 1.22 | 3.77 | 220.0 KB | http-429-wow.command.busy 215 |
| probe2-head | head | 586.1/s | 586.1/s | 11722 | 0 | 104.4 ms | 581.6 ms | 921.6 ms | n/a | 1171.5 ms | 0.89 cores | kafka 49% · mongo 147% | 0.64 of 1 | mongo 1.47 | 4.86 | 252.9 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 310.8/s | 310.8/s | 12432 | 0 | 876.5 ms | 1531.9 ms | 1916.9 ms | n/a | 2474.0 ms | 0.85 cores | kafka 44% · mongo 166% | 0.26 of 1 | mongo 1.66 | 9.47 | 559.8 KB | - |
| r1-head | head | 586.1/s | 586.1/s | 23444 | 0 | 41.2 ms | 280.6 ms | 389.1 ms | n/a | 493.6 ms | 0.78 cores | kafka 52% · mongo 156% | 0.33 of 1 | mongo 1.56 | 4.88 | 250.8 KB | - |
| r2-head | head | 586.1/s | 586.1/s | 23444 | 0 | 39.4 ms | 272.4 ms | 454.7 ms | n/a | 774.1 ms | 0.77 cores | kafka 52% · mongo 156% | 0.33 of 1 | mongo 1.56 | 4.87 | 253.2 KB | - |
| r2-base | base | 310.8/s | 310.8/s | 12432 | 0 | 798.7 ms | 1458.2 ms | 1753.1 ms | n/a | 2211.8 ms | 0.86 cores | kafka 41% · mongo 167% | 0.24 of 1 | mongo 1.67 | 9.48 | 574.4 KB | - |
| r3-base | base | 310.8/s | 310.8/s | 12432 | 0 | 802.8 ms | 1490.9 ms | 1785.9 ms | n/a | 2474.0 ms | 0.85 cores | kafka 43% · mongo 166% | 0.24 of 1 | mongo 1.66 | 9.48 | 569.2 KB | - |
| r3-head | head | 586.1/s | 586.1/s | 23444 | 0 | 44.8 ms | 247.8 ms | 378.9 ms | n/a | 655.4 ms | 0.77 cores | kafka 45% · mongo 160% | 0.35 of 1 | mongo 1.60 | 4.83 | 248.5 KB | - |
| e1-base | base | 310.8/s | 310.8/s | 12432 | 0 | 733.2 ms | 1318.9 ms | 1589.2 ms | n/a | 2392.1 ms | 0.88 cores | kafka 41% · mongo 166% | 0.26 of 1 | mongo 1.66 | 9.49 | 585.4 KB | - |
| e1-head | head | 310.8/s | 310.8/s | 12432 | 0 | 11.5 ms | 79.9 ms | 153.6 ms | n/a | 276.5 ms | 0.68 cores | kafka 53% · mongo 145% | 0.22 of 1 | mongo 1.45 | 8.56 | 331.9 KB | - |
| e2-head | head | 310.8/s | 310.8/s | 12432 | 0 | 10.8 ms | 68.6 ms | 136.2 ms | n/a | 236.5 ms | 0.70 cores | kafka 52% · mongo 143% | 0.22 of 1 | mongo 1.43 | 8.53 | 335.5 KB | - |
| e2-base | base | 310.8/s | 310.8/s | 12432 | 0 | 798.7 ms | 1400.8 ms | 1646.6 ms | n/a | 1916.9 ms | 0.85 cores | kafka 39% · mongo 170% | 0.24 of 1 | mongo 1.70 | 9.46 | 571.8 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.886 | 0.047 | 0.183 | 0.203 | - | 0.200 | 0.515 | 0.448 |
| 2 | 1.886 | 0.049 | 0.187 | 0.259 | - | 0.350 | 0.514 | 0.441 |
| 3 | 1.886 | 0.056 | 0.166 | 0.212 | - | 0.265 | 0.509 | 0.437 |

Pairs at one rate for both arms, 310.8/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.016 | 0.061 | 0.097 | - | 0.116 | 0.903 | 0.567 |
| 2 | 1.000 | 0.014 | 0.049 | 0.083 | - | 0.123 | 0.903 | 0.587 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -28.4% (±25.0%) |
| e3-mongo@100k | r2-head | steady | **p99 > 10× p50** | p50 39.4 ms, p99 454.7 ms |
| e3-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -25.6% (±25.0%) |
| e3-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +28.5% (±25.0%) |
| e3-mongo@100k | e1-head | steady | **p99 > 10× p50** | p50 11.5 ms, p99 153.6 ms |
| e3-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 10.8 ms, p99 136.2 ms |


### e3-mongo@1k

E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.

E3, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 522.8/s | 2048 | 235.2/s | 45% |
| head | 949.5/s | 128 | 320.5/s | 34% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 418.2/s | 30.4/s | 607 | 7757 | 8290.3 ms | 10485.8 ms | 10747.9 ms | n/a | 11148.5 ms | 1.49 cores | kafka 67% · mongo 78% | 0.57 of 1 | server 1.49 | 96.88 | 253.7 KB | http-504-wow.wait.timeout 7757 |
| probe2-base | base | 313.7/s | 278.6/s | 5573 | 701 | 4849.7 ms | 9109.5 ms | 10027.0 ms | n/a | 10682.4 ms | 1.37 cores | kafka 59% · mongo 97% | 0.47 of 1 | server 1.37 | 10.52 | 375.9 KB | http-504-wow.wait.timeout 701 |
| probe3-base | base | 235.2/s | 234.6/s | 4692 | 12 | 2408.4 ms | 6357.0 ms | 8716.3 ms | n/a | 10027.0 ms | 1.33 cores | kafka 63% · mongo 98% | 0.35 of 1 | server 1.33 | 12.56 | 446.9 KB | http-504-wow.wait.timeout 12 |
| probe1-head | head | 759.6/s | 0.7/s | 14 | 15178 | 10485.8 ms | 12696.5 ms | 12696.5 ms | n/a | 12696.5 ms | 1.59 cores | kafka 72% · mongo 59% | 0.81 of 1 | server 1.59 | 4144.96 | 147.8 KB | http-504-wow.wait.timeout 10314 · http-429-wow.command.busy 4864 |
| probe2-head | head | 569.7/s | 14.8/s | 296 | 11098 | 9371.6 ms | 10813.4 ms | 11206.7 ms | n/a | 11534.5 ms | 1.53 cores | kafka 69% · mongo 66% | 0.67 of 1 | server 1.53 | 195.03 | 238.0 KB | http-504-wow.wait.timeout 11077 · http-429-wow.command.busy 21 |
| probe3-head | head | 427.3/s | 410.1/s | 8201 | 345 | 4194.3 ms | 8847.4 ms | 9961.5 ms | n/a | 10485.8 ms | 1.57 cores | kafka 61% · mongo 80% | 0.59 of 1 | server 1.57 | 7.25 | 311.6 KB | http-504-wow.wait.timeout 345 |
| probe4-head | head | 320.5/s | 320.5/s | 6410 | 0 | 216.1 ms | 1892.4 ms | 2588.7 ms | n/a | 3686.4 ms | 1.38 cores | kafka 63% · mongo 91% | 0.40 of 1 | server 1.38 | 9.13 | 305.6 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 235.2/s | 235.2/s | 9408 | 0 | 585.7 ms | 1687.6 ms | 2392.1 ms | n/a | 2949.1 ms | 1.43 cores | kafka 38% · mongo 118% | 0.23 of 1 | server 1.43 | 12.71 | 507.3 KB | - |
| r1-head | head | 320.5/s | 320.5/s | 12820 | 0 | 33.8 ms | 348.2 ms | 565.2 ms | n/a | 921.6 ms | 1.33 cores | kafka 53% · mongo 106% | 0.26 of 1 | server 1.33 | 9.12 | 294.7 KB | - |
| r2-head | head | 320.5/s | 320.5/s | 12820 | 0 | 24.6 ms | 220.2 ms | 374.8 ms | n/a | 679.9 ms | 1.29 cores | kafka 47% · mongo 111% | 0.24 of 1 | server 1.29 | 8.94 | 302.1 KB | - |
| r2-base | base | 235.2/s | 235.2/s | 9408 | 0 | 544.8 ms | 1417.2 ms | 1957.9 ms | n/a | 3604.5 ms | 1.35 cores | kafka 43% · mongo 122% | 0.24 of 1 | server 1.35 | 12.74 | 538.5 KB | - |
| r3-base | base | 235.2/s | 235.2/s | 9408 | 0 | 573.4 ms | 1728.5 ms | 2555.9 ms | n/a | 3604.5 ms | 1.34 cores | kafka 44% · mongo 120% | 0.23 of 1 | server 1.34 | 12.66 | 516.6 KB | - |
| r3-head | head | 320.5/s | 320.5/s | 12820 | 0 | 30.0 ms | 335.9 ms | 548.9 ms | n/a | 872.4 ms | 1.33 cores | kafka 49% · mongo 108% | 0.26 of 1 | server 1.33 | 9.03 | 297.9 KB | - |
| e1-base | base | 235.2/s | 235.2/s | 9408 | 0 | 460.8 ms | 1417.2 ms | 2113.5 ms | n/a | 2949.1 ms | 1.31 cores | kafka 41% · mongo 124% | 0.23 of 1 | server 1.31 | 12.58 | 527.4 KB | - |
| e1-head | head | 235.2/s | 235.2/s | 9408 | 0 | 15.6 ms | 167.9 ms | 423.9 ms | n/a | 720.9 ms | 1.20 cores | kafka 54% · mongo 106% | 0.23 of 1 | server 1.20 | 11.88 | 343.5 KB | - |
| e2-head | head | 235.2/s | 235.2/s | 9408 | 0 | 15.6 ms | 139.3 ms | 250.9 ms | n/a | 477.2 ms | 1.22 cores | kafka 50% · mongo 106% | 0.27 of 1 | server 1.22 | 11.79 | 342.9 KB | - |
| e2-base | base | 235.2/s | 235.2/s | 9408 | 0 | 548.9 ms | 1359.9 ms | 1826.8 ms | n/a | 2457.6 ms | 1.38 cores | kafka 41% · mongo 120% | 0.22 of 1 | server 1.38 | 12.68 | 527.4 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.363 | 0.058 | 0.206 | 0.236 | - | 0.312 | 0.718 | 0.581 |
| 2 | 1.363 | 0.045 | 0.155 | 0.191 | - | 0.189 | 0.702 | 0.561 |
| 3 | 1.363 | 0.052 | 0.194 | 0.215 | - | 0.242 | 0.713 | 0.577 |

Pairs at one rate for both arms, 235.2/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.034 | 0.118 | 0.201 | - | 0.244 | 0.945 | 0.651 |
| 2 | 1.000 | 0.028 | 0.102 | 0.137 | - | 0.194 | 0.930 | 0.650 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -46.5% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -48.8% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 33.8 ms, p99 565.2 ms |
| e3-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -53.5% (±25.0%) |
| e3-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 24.6 ms, p99 374.8 ms |
| e3-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -36.8% (±25.0%) |
| e3-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -42.0% (±25.0%) |
| e3-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -46.6% (±25.0%) |
| e3-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 30.0 ms, p99 548.9 ms |
| e3-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -54.5% (±25.0%) |
| e3-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 15.6 ms, p99 423.9 ms |
| e3-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -35.6% (±25.0%) |
| e3-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 15.6 ms, p99 250.9 ms |
| e3-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -39.4% (±25.0%) |


### e3-pg@100k

E3 saga chain on PostgreSQL: head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (31–56 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1250.4/s | 64 | 750.2/s | 60% |
| head | 2410.1/s | 1024 | 1446.1/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1000.3/s | 848.9/s | 16978 | 3028 | 3801.1 ms | 5275.6 ms | 6127.6 ms | n/a | 7241.7 ms | 1.26 cores | kafka 40% · postgres 101% | 0.54 of 1 | server 1.26 | 3.15 | 247.4 KB | http-429-wow.command.busy 3028 |
| probe2-base | base | 750.2/s | 750.2/s | 15004 | 0 | 1204.2 ms | 2981.9 ms | 3227.6 ms | n/a | 3538.9 ms | 1.11 cores | kafka 44% · postgres 112% | 0.57 of 1 | postgres 1.12 | 3.57 | 313.3 KB | - |
| probe1-head | head | 1928.1/s | 1497.4/s | 29948 | 8614 | 12189.7 ms | 15728.6 ms | 16384.0 ms | n/a | 16908.3 ms | 0.98 cores | kafka 47% · postgres 89% | 0.96 of 1 | server 0.98 | 1.57 | 140.1 KB | generator-overload 5795 · http-429-wow.command.busy 1525 · exception-ConnectException 1294 |
| probe2-head | head | 1446.1/s | 1446.1/s | 28922 | 0 | 5505.0 ms | 6324.2 ms | 8454.1 ms | n/a | 8847.4 ms | 1.01 cores | kafka 50% · postgres 99% | 0.96 of 1 | server 1.01 | 1.73 | 221.1 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 750.2/s | 750.2/s | 30008 | 0 | 2039.8 ms | 2965.5 ms | 3342.3 ms | n/a | 3751.9 ms | 1.15 cores | kafka 46% · postgres 110% | 0.40 of 1 | server 1.15 | 3.61 | 315.9 KB | - |
| r1-head | head | 1446.1/s | 1446.1/s | 57844 | 0 | 67.6 ms | 368.6 ms | 540.7 ms | n/a | 692.2 ms | 0.96 cores | kafka 49% · postgres 110% | 0.58 of 1 | postgres 1.10 | 1.76 | 193.7 KB | - |
| r2-head | head | 1446.1/s | 1446.1/s | 57844 | 0 | 53.2 ms | 380.9 ms | 614.4 ms | n/a | 831.5 ms | 0.99 cores | kafka 46% · postgres 111% | 0.57 of 1 | postgres 1.11 | 1.77 | 193.4 KB | - |
| r2-base | base | 750.2/s | 750.2/s | 30008 | 0 | 8.2 ms | 216.1 ms | 401.4 ms | n/a | 577.5 ms | 1.03 cores | kafka 41% · postgres 105% | 0.30 of 1 | postgres 1.05 | 3.31 | 297.1 KB | - |
| r3-base | base | 750.2/s | 750.2/s | 30008 | 0 | 6.3 ms | 102.9 ms | 331.8 ms | n/a | 602.1 ms | 1.04 cores | kafka 39% · postgres 105% | 0.29 of 1 | postgres 1.05 | 3.30 | 298.1 KB | - |
| r3-head | head | 1446.1/s | 1446.1/s | 57844 | 0 | 62.0 ms | 421.9 ms | 647.2 ms | n/a | 823.3 ms | 0.94 cores | kafka 48% · postgres 110% | 0.56 of 1 | postgres 1.10 | 1.74 | 191.9 KB | - |
| e1-base | base | 750.2/s | 750.2/s | 30008 | 0 | 34.0 ms | 331.8 ms | 532.5 ms | n/a | 766.0 ms | 1.02 cores | kafka 48% · postgres 110% | 0.33 of 1 | postgres 1.10 | 3.47 | 287.6 KB | - |
| e1-head | head | 750.2/s | 750.2/s | 30008 | 0 | 6.2 ms | 63.5 ms | 242.7 ms | n/a | 397.3 ms | 0.93 cores | kafka 51% · postgres 96% | 0.31 of 1 | postgres 0.96 | 3.19 | 214.7 KB | - |
| e2-head | head | 750.2/s | 750.2/s | 30008 | 0 | 4.4 ms | 57.3 ms | 180.2 ms | n/a | 397.3 ms | 0.96 cores | kafka 45% · postgres 95% | 0.30 of 1 | server 0.96 | 3.14 | 215.5 KB | - |
| e2-base | base | 750.2/s | 750.2/s | 30008 | 0 | 32.0 ms | 460.8 ms | 593.9 ms | n/a | 782.3 ms | 1.01 cores | kafka 44% · postgres 110% | 0.34 of 1 | postgres 1.10 | 3.41 | 289.4 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.928 | 0.033 | 0.124 | 0.162 | - | 0.184 | 0.489 | 0.613 |
| 2 | 1.928 | 6.501 | 1.763 | 1.531 | - | 1.440 | 0.533 | 0.651 |
| 3 | 1.928 | 9.779 | 4.100 | 1.951 | - | 1.367 | 0.529 | 0.644 |

Pairs at one rate for both arms, 750.2/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.181 | 0.191 | 0.456 | - | 0.519 | 0.920 | 0.747 |
| 2 | 1.000 | 0.136 | 0.124 | 0.303 | - | 0.508 | 0.921 | 0.745 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -25.3% (±25.0%) |
| e3-pg@100k | r2-head | steady | **p99 > 10× p50** | p50 53.2 ms, p99 614.4 ms |
| e3-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +47.0% (±25.0%) |
| e3-pg@100k | r2-base | steady | **p99 > 10× p50** | p50 8.2 ms, p99 401.4 ms |
| e3-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -37.6% (±25.0%) |
| e3-pg@100k | r3-base | steady | **p99 > 10× p50** | p50 6.3 ms, p99 331.8 ms |
| e3-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.2% (±25.0%) |
| e3-pg@100k | r3-head | steady | **p99 > 10× p50** | p50 62.0 ms, p99 647.2 ms |
| e3-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +88.3% (±25.0%) |
| e3-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 34.0 ms, p99 532.5 ms |
| e3-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -28.2% (±25.0%) |
| e3-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 6.2 ms, p99 242.7 ms |
| e3-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -50.7% (±25.0%) |
| e3-pg@100k | e2-head | steady | **p99 > 10× p50** | p50 4.4 ms, p99 180.2 ms |
| e3-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -28.1% (±25.0%) |
| e3-pg@100k | e2-base | steady | **p99 > 10× p50** | p50 32.0 ms, p99 593.9 ms |


### e3-pg@1k

E3 saga chain on PostgreSQL: head against baseline.

E3, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 787.4/s | 64 | 265.7/s | 34% |
| head | 1301.5/s | 1024 | 439.3/s | 34% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 629.9/s | 1.8/s | 35 | 12563 | 9568.3 ms | 11010.0 ms | 11288.2 ms | n/a | 11288.2 ms | 1.77 cores | kafka 71% · postgres 41% | 0.76 of 1 | server 1.77 | 1648.41 | 131.7 KB | http-504-wow.wait.timeout 7525 · http-429-wow.command.busy 5038 |
| probe2-base | base | 472.4/s | 66.6/s | 1332 | 8116 | 8159.2 ms | 10551.3 ms | 10944.5 ms | n/a | 11212.6 ms | 1.68 cores | kafka 67% · postgres 52% | 0.67 of 1 | server 1.68 | 43.24 | 192.6 KB | http-504-wow.wait.timeout 8116 |
| probe3-base | base | 354.3/s | 340.8/s | 6816 | 270 | 3326.0 ms | 8290.3 ms | 9764.9 ms | n/a | 10223.6 ms | 1.65 cores | kafka 65% · postgres 61% | 0.52 of 1 | server 1.65 | 8.51 | 292.4 KB | http-504-wow.wait.timeout 270 |
| probe4-base | base | 265.7/s | 265.7/s | 5314 | 0 | 57.6 ms | 1753.1 ms | 2703.4 ms | n/a | 3555.3 ms | 1.60 cores | kafka 71% · postgres 61% | 0.39 of 1 | server 1.60 | 11.00 | 313.9 KB | - |
| probe1-head | head | 1041.2/s | 2.8/s | 55 | 20769 | 13238.3 ms | 15071.9 ms | 15071.9 ms | n/a | 15071.9 ms | 1.81 cores | kafka 80% · postgres 29% | 0.94 of 1 | server 1.81 | 1053.26 | 107.5 KB | http-504-wow.wait.timeout 11698 · http-429-wow.command.busy 9071 |
| probe2-head | head | 780.9/s | 5.0/s | 101 | 15517 | 10747.9 ms | 12255.2 ms | 12648.4 ms | n/a | 12697.0 ms | 1.77 cores | kafka 81% · postgres 31% | 0.89 of 1 | server 1.77 | 573.44 | 137.4 KB | http-504-wow.wait.timeout 11755 · http-429-wow.command.busy 3762 |
| probe3-head | head | 585.7/s | 39.4/s | 788 | 10926 | 9044.0 ms | 10616.8 ms | 11075.6 ms | n/a | 11498.3 ms | 1.72 cores | kafka 81% · postgres 39% | 0.72 of 1 | server 1.72 | 74.03 | 204.2 KB | http-504-wow.wait.timeout 10926 |
| probe4-head | head | 439.3/s | 435.4/s | 8707 | 78 | 3129.3 ms | 7602.2 ms | 9437.2 ms | n/a | 10223.6 ms | 1.73 cores | kafka 77% · postgres 44% | 0.67 of 1 | server 1.73 | 6.76 | 269.4 KB | http-504-wow.wait.timeout 78 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 265.7/s | 265.7/s | 10628 | 0 | 4.5 ms | 31.0 ms | 102.4 ms | n/a | 247.8 ms | 1.23 cores | kafka 56% · postgres 64% | 0.23 of 1 | server 1.23 | 9.15 | 281.9 KB | - |
| r1-head | head | 439.3/s | 439.3/s | 17571 | 0 | 29.8 ms | 256.0 ms | 458.8 ms | n/a | 692.2 ms | 1.57 cores | kafka 52% · postgres 79% | 0.29 of 1 | server 1.57 | 6.56 | 219.9 KB | - |
| r2-head | head | 439.3/s | 439.3/s | 17571 | 0 | 31.0 ms | 385.0 ms | 618.5 ms | n/a | 942.1 ms | 1.57 cores | kafka 55% · postgres 78% | 0.29 of 1 | server 1.57 | 6.61 | 220.1 KB | - |
| r2-base | base | 265.7/s | 265.7/s | 10628 | 0 | 4.2 ms | 28.9 ms | 84.0 ms | n/a | 303.1 ms | 1.20 cores | kafka 60% · postgres 65% | 0.21 of 1 | server 1.20 | 9.23 | 280.7 KB | - |
| r3-base | base | 265.7/s | 265.7/s | 10628 | 0 | 4.3 ms | 40.2 ms | 116.7 ms | n/a | 385.0 ms | 1.20 cores | kafka 59% · postgres 64% | 0.24 of 1 | server 1.20 | 9.13 | 285.0 KB | - |
| r3-head | head | 439.3/s | 439.3/s | 17571 | 0 | 25.7 ms | 307.2 ms | 505.9 ms | n/a | 733.2 ms | 1.55 cores | kafka 54% · postgres 78% | 0.31 of 1 | server 1.55 | 6.54 | 223.2 KB | - |
| e1-base | base | 265.7/s | 265.7/s | 10628 | 0 | 3.6 ms | 20.6 ms | 63.2 ms | n/a | 272.4 ms | 1.16 cores | kafka 57% · postgres 63% | 0.20 of 1 | server 1.16 | 8.86 | 284.0 KB | - |
| e1-head | head | 265.7/s | 265.7/s | 10628 | 0 | 6.6 ms | 63.0 ms | 152.6 ms | n/a | 303.1 ms | 1.43 cores | kafka 57% · postgres 64% | 0.27 of 1 | server 1.43 | 9.96 | 230.6 KB | - |
| e2-head | head | 265.7/s | 265.7/s | 10628 | 0 | 4.5 ms | 36.6 ms | 112.1 ms | n/a | 264.2 ms | 1.29 cores | kafka 58% · postgres 63% | 0.22 of 1 | server 1.29 | 9.41 | 228.6 KB | - |
| e2-base | base | 265.7/s | 265.7/s | 10628 | 0 | 5.3 ms | 53.0 ms | 119.8 ms | n/a | 340.0 ms | 1.28 cores | kafka 62% · postgres 64% | 0.27 of 1 | server 1.28 | 9.57 | 282.2 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.653 | 6.565 | 8.265 | 4.480 | - | 2.793 | 0.717 | 0.780 |
| 2 | 1.653 | 7.335 | 13.310 | 7.366 | - | 3.108 | 0.715 | 0.784 |
| 3 | 1.653 | 5.957 | 7.643 | 4.333 | - | 1.904 | 0.717 | 0.783 |

Pairs at one rate for both arms, 265.7/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.848 | 3.056 | 2.413 | - | 1.113 | 1.123 | 0.812 |
| 2 | 1.000 | 0.849 | 0.691 | 0.936 | - | 0.777 | 0.983 | 0.810 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -44.9% (±25.0%) |
| e3-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 4.5 ms, p99 102.4 ms |
| e3-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -59.7% (±25.0%) |
| e3-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 29.8 ms, p99 458.8 ms |
| e3-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -37.5% (±25.0%) |
| e3-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 31.0 ms, p99 618.5 ms |
| e3-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -45.4% (±25.0%) |
| e3-pg@1k | r2-base | steady | **p99 > 10× p50** | p50 4.2 ms, p99 84.0 ms |
| e3-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -51.4% (±25.0%) |
| e3-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 4.3 ms, p99 116.7 ms |
| e3-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -57.5% (±25.0%) |
| e3-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 25.7 ms, p99 505.9 ms |
| e3-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -45.2% (±25.0%) |
| e3-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 3.6 ms, p99 63.2 ms |
| e3-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -31.7% (±25.0%) |
| e3-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 6.6 ms, p99 152.6 ms |
| e3-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.3% (±25.0%) |
| e3-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 4.5 ms, p99 112.1 ms |
| e3-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -51.3% (±25.0%) |
| e3-pg@1k | e2-base | steady | **p99 > 10× p50** | p50 5.3 ms, p99 119.8 ms |
| e3-pg@1k | probe1-base | overload (probe) | **tail read the store** | gap=3 |
| e3-pg@1k | probe2-base | overload (probe) | **tail read the store** | gap=2 |


### mixed-mongo@100k

Reads and writes together on MongoDB (E1 deposits 50%, E4 point reads 40%, E4 lists 10%): whether the query bulkheads keep the writes fast. Head against baseline.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (59–177 s), then waited until the nodes were quiet again (4–6 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 843.1/s | 64 | 505.9/s | 60% |
| head | 1910.3/s | 256 | 859.6/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 674.5/s | 627.5/s | 12551 | 939 | 2056.2 ms | 5275.6 ms | 6193.2 ms | n/a | 7045.1 ms | 1.04 cores | kafka 38% · mongo 133% | 0.85 of 1 | mongo 1.33 | 4.39 | 185.9 KB | E4-list:read-http-429 939 |
| probe2-base | base | 505.9/s | 505.9/s | 10117 | 1 | 24.7 ms | 393.2 ms | 704.5 ms | n/a | 1130.5 ms | 0.99 cores | kafka 46% · mongo 120% | 0.64 of 1 | mongo 1.20 | 5.25 | 199.4 KB | E4-list:read-http-429 1 |
| probe1-head | head | 1528.2/s | 824.3/s | 16485 | 14079 | 17432.6 ms | 19791.9 ms | 19791.9 ms | n/a | 19998.5 ms | 0.84 cores | kafka 58% · mongo 97% | 0.95 of 1 | mongo 0.97 | 2.89 | 48.7 KB | generator-overload 6556 · E1:exception-ConnectException 3768 · E4-state:exception-ConnectException 3002 · E4-list:exception-ConnectException 739 · E4-list:read-http-429 14 |
| probe2-head | head | 1146.2/s | 1134.3/s | 22685 | 239 | 8257.5 ms | 11010.0 ms | 11599.9 ms | n/a | 11993.1 ms | 0.89 cores | kafka 54% · mongo 102% | 0.94 of 1 | mongo 1.02 | 2.17 | 91.0 KB | E4-list:read-http-429 239 |
| probe3-head | head | 859.6/s | 859.2/s | 17184 | 8 | 2506.8 ms | 4849.7 ms | 4882.4 ms | n/a | 4948.0 ms | 0.96 cores | kafka 49% · mongo 106% | 0.93 of 1 | mongo 1.06 | 2.91 | 110.7 KB | E4-list:read-http-429 8 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 505.9/s | 505.9/s | 20236 | 0 | 5.1 ms | 65.3 ms | 203.8 ms | n/a | 464.9 ms | 0.76 cores | kafka 50% · mongo 124% | 0.31 of 1 | mongo 1.24 | 4.94 | 206.4 KB | - |
| r1-head | head | 859.6/s | 859.6/s | 34384 | 0 | 4.2 ms | 60.9 ms | 153.6 ms | n/a | 256.0 ms | 0.81 cores | kafka 54% · mongo 116% | 0.52 of 1 | mongo 1.16 | 2.92 | 106.3 KB | - |
| r2-head | head | 859.6/s | 859.6/s | 34384 | 0 | 3.3 ms | 32.5 ms | 93.7 ms | n/a | 272.4 ms | 0.76 cores | kafka 54% · mongo 116% | 0.48 of 1 | mongo 1.16 | 2.86 | 108.3 KB | - |
| r2-base | base | 505.9/s | 505.1/s | 20205 | 31 | 7.6 ms | 122.4 ms | 286.7 ms | n/a | 725.0 ms | 0.82 cores | kafka 49% · mongo 129% | 0.36 of 1 | mongo 1.29 | 5.14 | 203.3 KB | E4-list:read-http-429 31 |
| r3-base | base | 505.9/s | 505.8/s | 20233 | 3 | 5.9 ms | 110.1 ms | 299.0 ms | n/a | 634.9 ms | 0.77 cores | kafka 52% · mongo 127% | 0.31 of 1 | mongo 1.27 | 5.05 | 204.8 KB | E4-list:read-http-429 3 |
| r3-head | head | 859.6/s | 859.6/s | 34384 | 0 | 3.5 ms | 30.6 ms | 86.5 ms | n/a | 214.0 ms | 0.79 cores | kafka 54% · mongo 118% | 0.47 of 1 | mongo 1.18 | 2.91 | 108.7 KB | - |
| e1-base | base | 505.9/s | 505.9/s | 20236 | 0 | 5.8 ms | 101.4 ms | 284.7 ms | n/a | 737.3 ms | 0.77 cores | kafka 50% · mongo 126% | 0.31 of 1 | mongo 1.26 | 5.01 | 203.6 KB | - |
| e1-head | head | 505.9/s | 505.9/s | 20236 | 0 | 2.4 ms | 17.3 ms | 67.6 ms | n/a | 159.7 ms | 0.61 cores | kafka 60% · mongo 96% | 0.33 of 1 | mongo 0.96 | 4.29 | 116.7 KB | - |
| e2-head | head | 505.9/s | 505.9/s | 20236 | 0 | 2.0 ms | 11.0 ms | 52.7 ms | n/a | 164.9 ms | 0.57 cores | kafka 57% · mongo 91% | 0.27 of 1 | mongo 0.91 | 4.04 | 116.6 KB | - |
| e2-base | base | 505.9/s | 505.9/s | 20236 | 0 | 6.6 ms | 98.8 ms | 235.5 ms | n/a | 909.3 ms | 0.80 cores | kafka 49% · mongo 128% | 0.33 of 1 | mongo 1.28 | 5.08 | 204.2 KB | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed | E4-list p50 | E4-list p99 | E4-list failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 8.9 ms | 278.5 ms | 0 | 1.9 ms | 68.1 ms | 0 | 2.6 ms | 104.4 ms | 0 |
| r1-head | head | 5.9 ms | 189.4 ms | 0 | 2.8 ms | 110.6 ms | 0 | 3.7 ms | 113.7 ms | 0 |
| r2-head | head | 4.5 ms | 114.2 ms | 0 | 2.1 ms | 58.6 ms | 0 | 3.0 ms | 62.0 ms | 0 |
| r2-base | base | 14.3 ms | 370.7 ms | 0 | 3.0 ms | 93.2 ms | 0 | 4.0 ms | 179.2 ms | 31 |
| r3-base | base | 10.7 ms | 364.5 ms | 0 | 2.4 ms | 181.2 ms | 0 | 3.3 ms | 237.6 ms | 3 |
| r3-head | head | 4.9 ms | 111.1 ms | 0 | 2.2 ms | 52.5 ms | 0 | 3.1 ms | 57.3 ms | 0 |
| e1-base | base | 10.8 ms | 378.9 ms | 0 | 2.3 ms | 116.2 ms | 0 | 3.1 ms | 102.4 ms | 0 |
| e1-head | head | 3.2 ms | 79.9 ms | 0 | 1.7 ms | 48.4 ms | 0 | 2.4 ms | 54.3 ms | 0 |
| e2-head | head | 2.4 ms | 69.1 ms | 0 | 1.4 ms | 29.2 ms | 0 | 1.9 ms | 31.0 ms | 0 |
| e2-base | base | 12.6 ms | 311.3 ms | 0 | 2.6 ms | 79.9 ms | 0 | 3.6 ms | 110.1 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.699 | 0.824 | 0.933 | 0.754 | - | 0.551 | 0.592 | 0.515 |
| 2 | 1.702 | 0.433 | 0.266 | 0.327 | - | 0.376 | 0.556 | 0.533 |
| 3 | 1.699 | 0.586 | 0.278 | 0.289 | - | 0.337 | 0.577 | 0.531 |

Pairs at one rate for both arms, 505.9/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.420 | 0.170 | 0.237 | - | 0.217 | 0.857 | 0.573 |
| 2 | 1.000 | 0.307 | 0.111 | 0.224 | - | 0.181 | 0.795 | 0.571 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -25.7% (±25.0%) |
| mixed-mongo@100k | r1-base | steady | **p99 > 10× p50** | p50 5.1 ms, p99 203.8 ms |
| mixed-mongo@100k | r1-head | steady | **p99 > 10× p50** | p50 4.2 ms, p99 153.6 ms |
| mixed-mongo@100k | r2-head | steady | **p99 > 10× p50** | p50 3.3 ms, p99 93.7 ms |
| mixed-mongo@100k | r2-base | steady | **p99 > 10× p50** | p50 7.6 ms, p99 286.7 ms |
| mixed-mongo@100k | r3-base | steady | **p99 > 10× p50** | p50 5.9 ms, p99 299.0 ms |
| mixed-mongo@100k | r3-head | steady | **p99 > 10× p50** | p50 3.5 ms, p99 86.5 ms |
| mixed-mongo@100k | e1-base | steady | **p99 > 10× p50** | p50 5.8 ms, p99 284.7 ms |
| mixed-mongo@100k | e1-head | steady | **p99 > 10× p50** | p50 2.4 ms, p99 67.6 ms |
| mixed-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 2.0 ms, p99 52.7 ms |
| mixed-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -34.2% (±25.0%) |
| mixed-mongo@100k | e2-base | steady | **p99 > 10× p50** | p50 6.6 ms, p99 235.5 ms |


### mixed-mongo@1k

Reads and writes together on MongoDB (E1 deposits 50%, E4 point reads 40%, E4 lists 10%): whether the query bulkheads keep the writes fast. Head against baseline.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: Intel(R) Xeon(R) 6973P-C, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1588.5/s | 128 | 536.1/s | 34% |
| head | 3271.3/s | 128 | 1104.0/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1270.8/s | 1027.1/s | 20542 | 4874 | 2048.0 ms | 9961.5 ms | 15073.3 ms | n/a | 19791.9 ms | 1.59 cores | kafka 57% · mongo 71% | 0.79 of 1 | server 1.59 | 2.80 | 105.5 KB | E4-list:read-http-429 2318 · E1:http-429-wow.command.busy 2020 · E1:http-504-wow.wait.timeout 472 · E1:exception-ConnectException 31 · E4-state:exception-ConnectException 26 · E4-list:exception-ConnectException 7 |
| probe2-base | base | 953.1/s | 902.5/s | 18049 | 1012 | 395.3 ms | 3440.6 ms | 5177.3 ms | n/a | 6357.0 ms | 1.53 cores | kafka 56% · mongo 83% | 0.60 of 1 | server 1.53 | 3.24 | 140.3 KB | E4-list:read-http-429 1012 |
| probe3-base | base | 714.8/s | 703.0/s | 14059 | 237 | 63.0 ms | 864.3 ms | 1359.9 ms | n/a | 2023.4 ms | 1.55 cores | kafka 50% · mongo 86% | 0.48 of 1 | server 1.55 | 4.14 | 164.4 KB | E4-list:read-http-429 237 |
| probe4-base | base | 536.1/s | 532.7/s | 10654 | 67 | 9.0 ms | 247.8 ms | 782.3 ms | n/a | 1138.7 ms | 1.48 cores | kafka 51% · mongo 82% | 0.43 of 1 | server 1.48 | 5.29 | 174.3 KB | E4-list:read-http-429 67 |
| probe1-head | head | 2617.0/s | 1059.8/s | 21196 | 31144 | 12582.9 ms | 14745.6 ms | 15597.6 ms | n/a | 16103.4 ms | 1.39 cores | kafka 50% · mongo 60% | 0.96 of 1 | server 1.39 | 2.35 | 38.5 KB | generator-overload 29742 · E4-list:read-http-429 1402 |
| probe2-head | head | 1962.8/s | 1427.6/s | 28552 | 10704 | 11862.0 ms | 16515.1 ms | 17825.8 ms | n/a | 18481.2 ms | 1.60 cores | kafka 54% · mongo 63% | 0.94 of 1 | server 1.60 | 1.94 | 66.5 KB | generator-overload 8460 · E4-list:read-http-429 1724 · E1:exception-ConnectException 258 · E4-state:exception-ConnectException 208 · E4-list:exception-ConnectException 54 |
| probe3-head | head | 1472.1/s | 1371.8/s | 27437 | 2005 | 1441.8 ms | 3751.9 ms | 4522.0 ms | n/a | 14745.6 ms | 1.72 cores | kafka 53% · mongo 66% | 0.84 of 1 | server 1.72 | 2.12 | 102.6 KB | E4-list:read-http-429 2005 |
| probe4-head | head | 1104.0/s | 1082.1/s | 21642 | 438 | 158.7 ms | 1384.4 ms | 1998.8 ms | n/a | 2670.6 ms | 1.69 cores | kafka 53% · mongo 71% | 0.64 of 1 | server 1.69 | 2.70 | 113.8 KB | E4-list:read-http-429 438 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 536.1/s | 536.1/s | 21443 | 0 | 2.9 ms | 12.0 ms | 42.5 ms | n/a | 109.1 ms | 0.80 cores | kafka 50% · mongo 85% | 0.21 of 1 | mongo 0.85 | 4.00 | 167.9 KB | - |
| r1-head | head | 1104.0/s | 1104.0/s | 44160 | 0 | 5.0 ms | 113.2 ms | 290.8 ms | n/a | 614.4 ms | 1.37 cores | kafka 46% · mongo 92% | 0.41 of 1 | server 1.37 | 2.49 | 107.7 KB | - |
| r2-head | head | 1104.0/s | 1104.0/s | 44160 | 0 | 3.8 ms | 74.2 ms | 184.3 ms | n/a | 342.0 ms | 1.24 cores | kafka 46% · mongo 92% | 0.41 of 1 | server 1.24 | 2.37 | 109.4 KB | - |
| r2-base | base | 536.1/s | 536.1/s | 21443 | 0 | 2.9 ms | 19.6 ms | 104.4 ms | n/a | 436.2 ms | 0.72 cores | kafka 47% · mongo 82% | 0.20 of 1 | mongo 0.82 | 3.74 | 165.7 KB | - |
| r3-base | base | 536.1/s | 536.1/s | 21443 | 0 | 2.8 ms | 10.5 ms | 33.0 ms | n/a | 77.3 ms | 0.69 cores | kafka 44% · mongo 82% | 0.20 of 1 | mongo 0.82 | 3.63 | 168.4 KB | - |
| r3-head | head | 1104.0/s | 1104.0/s | 44160 | 0 | 4.2 ms | 72.7 ms | 216.1 ms | n/a | 491.5 ms | 1.33 cores | kafka 45% · mongo 89% | 0.42 of 1 | server 1.33 | 2.42 | 108.4 KB | - |
| e1-base | base | 536.1/s | 536.1/s | 21443 | 0 | 2.8 ms | 15.0 ms | 64.5 ms | n/a | 257.0 ms | 0.67 cores | kafka 46% · mongo 81% | 0.21 of 1 | mongo 0.81 | 3.61 | 166.0 KB | - |
| e1-head | head | 536.1/s | 536.1/s | 21443 | 0 | 1.6 ms | 4.9 ms | 20.0 ms | n/a | 86.5 ms | 0.54 cores | kafka 47% · mongo 63% | 0.19 of 1 | mongo 0.63 | 3.07 | 118.7 KB | - |
| e2-head | head | 536.1/s | 536.1/s | 21443 | 0 | 1.6 ms | 4.6 ms | 12.1 ms | n/a | 64.3 ms | 0.54 cores | kafka 50% · mongo 64% | 0.20 of 1 | mongo 0.64 | 3.13 | 118.0 KB | - |
| e2-base | base | 536.1/s | 536.1/s | 21443 | 0 | 2.9 ms | 16.2 ms | 55.3 ms | n/a | 145.4 ms | 0.72 cores | kafka 49% · mongo 81% | 0.23 of 1 | mongo 0.81 | 3.75 | 171.1 KB | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed | E4-list p50 | E4-list p99 | E4-list failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 4.0 ms | 56.6 ms | 0 | 1.1 ms | 22.7 ms | 0 | 1.5 ms | 21.6 ms | 0 |
| r1-head | head | 7.2 ms | 370.7 ms | 0 | 3.1 ms | 202.8 ms | 0 | 3.8 ms | 215.0 ms | 0 |
| r2-head | head | 5.4 ms | 217.1 ms | 0 | 2.2 ms | 120.8 ms | 0 | 2.8 ms | 135.2 ms | 0 |
| r2-base | base | 3.6 ms | 139.3 ms | 0 | 1.0 ms | 62.0 ms | 0 | 1.3 ms | 66.0 ms | 0 |
| r3-base | base | 3.6 ms | 43.5 ms | 0 | 1.0 ms | 15.7 ms | 0 | 1.2 ms | 17.9 ms | 0 |
| r3-head | head | 6.0 ms | 259.1 ms | 0 | 2.6 ms | 152.6 ms | 0 | 3.2 ms | 151.6 ms | 0 |
| e1-base | base | 3.5 ms | 89.1 ms | 0 | 1.0 ms | 35.3 ms | 0 | 1.2 ms | 39.2 ms | 0 |
| e1-head | head | 2.2 ms | 26.6 ms | 0 | 1.0 ms | 13.2 ms | 0 | 1.2 ms | 15.4 ms | 0 |
| e2-head | head | 2.2 ms | 15.9 ms | 0 | 1.0 ms | 7.4 ms | 0 | 1.2 ms | 8.1 ms | 0 |
| e2-base | base | 3.7 ms | 64.8 ms | 0 | 1.0 ms | 33.5 ms | 0 | 1.3 ms | 30.8 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 2.059 | 1.696 | 9.455 | 6.844 | - | 5.634 | 0.622 | 0.641 |
| 2 | 2.059 | 1.317 | 3.791 | 1.765 | - | 0.784 | 0.632 | 0.660 |
| 3 | 2.059 | 1.477 | 6.927 | 6.543 | - | 6.358 | 0.667 | 0.644 |

Pairs at one rate for both arms, 536.1/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.574 | 0.328 | 0.310 | - | 0.337 | 0.853 | 0.715 |
| 2 | 1.000 | 0.555 | 0.283 | 0.219 | - | 0.442 | 0.835 | 0.690 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -56.7% (±25.0%) |
| mixed-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 5.0 ms, p99 290.8 ms |
| mixed-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -48.0% (±25.0%) |
| mixed-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 3.8 ms, p99 184.3 ms |
| mixed-mongo@1k | r2-base | steady | **p99 > 10× p50** | p50 2.9 ms, p99 104.4 ms |
| mixed-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -51.0% (±25.0%) |
| mixed-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 4.2 ms, p99 216.1 ms |
| mixed-mongo@1k | e1-base | steady | **p99 > 10× p50** | p50 2.8 ms, p99 64.5 ms |
| mixed-mongo@1k | e2-base | steady | **p99 > 10× p50** | p50 2.9 ms, p99 55.3 ms |


### mixed-pg@100k

Reads and writes together on PostgreSQL, as mixed-mongo.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (52–85 s), then waited until the nodes were quiet again (4–5 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1911.1/s | 64 | 860.0/s | 45% |
| head | 2403.6/s | 64 | 1442.2/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1528.9/s | 1079.0/s | 21579 | 8999 | 15138.8 ms | 19660.8 ms | 21102.6 ms | n/a | 21694.1 ms | 1.01 cores | kafka 61% · postgres 59% | 0.95 of 1 | server 1.01 | 2.06 | 52.6 KB | generator-overload 5181 · E1:exception-ConnectException 1766 · E4-state:exception-ConnectException 1413 · E4-list:exception-ConnectException 358 · E4-list:read-http-429 281 |
| probe2-base | base | 1146.7/s | 1108.9/s | 22178 | 756 | 5963.8 ms | 6750.2 ms | 8585.2 ms | n/a | 8978.4 ms | 1.01 cores | kafka 54% · postgres 68% | 0.93 of 1 | server 1.01 | 2.02 | 87.3 KB | E4-list:read-http-429 756 |
| probe3-base | base | 860.0/s | 854.9/s | 17098 | 102 | 7.8 ms | 2211.8 ms | 2424.8 ms | n/a | 2490.4 ms | 0.96 cores | kafka 51% · postgres 59% | 0.85 of 1 | server 0.96 | 2.42 | 89.1 KB | E4-list:read-http-429 102 |
| probe1-head | head | 1922.9/s | 1015.5/s | 20309 | 18149 | 15007.7 ms | 19005.4 ms | 19136.5 ms | n/a | 19158.3 ms | 0.79 cores | kafka 62% · postgres 40% | 0.97 of 1 | load generator 0.97 | 1.78 | 28.0 KB | generator-overload 15045 · E1:exception-ConnectException 1371 · E4-state:exception-ConnectException 1103 · E4-list:read-http-429 374 · E4-list:exception-ConnectException 256 |
| probe2-head | head | 1442.2/s | 1430.6/s | 28612 | 232 | 11337.7 ms | 15007.7 ms | 15138.8 ms | n/a | 15388.9 ms | 0.96 cores | kafka 59% · postgres 50% | 0.95 of 1 | server 0.96 | 1.43 | 55.4 KB | E4-list:read-http-429 232 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 860.0/s | 860.0/s | 34400 | 0 | 2.1 ms | 9.9 ms | 29.1 ms | n/a | 100.4 ms | 0.82 cores | kafka 59% · postgres 59% | 0.44 of 1 | server 0.82 | 2.31 | 84.8 KB | - |
| r1-head | head | 1442.2/s | 1404.3/s | 56172 | 1515 | 11927.6 ms | 13959.2 ms | 17301.5 ms | n/a | 17994.4 ms | 0.81 cores | kafka 53% · postgres 62% | 0.95 of 1 | load generator 0.95 | 1.40 | 67.2 KB | generator-overload 1080 · E4-list:read-http-429 435 |
| r2-head | head | 1442.2/s | 1424.1/s | 56963 | 724 | 10551.3 ms | 11730.9 ms | 15269.9 ms | n/a | 15466.5 ms | 0.82 cores | kafka 55% · postgres 62% | 0.96 of 1 | load generator 0.96 | 1.40 | 70.8 KB | E4-list:read-http-429 724 |
| r2-base | base | 860.0/s | 860.0/s | 34400 | 0 | 2.1 ms | 8.4 ms | 20.5 ms | n/a | 106.5 ms | 0.81 cores | kafka 58% · postgres 58% | 0.44 of 1 | server 0.81 | 2.29 | 85.3 KB | - |
| r3-base | base | 860.0/s | 860.0/s | 34400 | 0 | 2.0 ms | 9.1 ms | 27.3 ms | n/a | 93.7 ms | 0.80 cores | kafka 58% · postgres 57% | 0.43 of 1 | server 0.80 | 2.26 | 85.9 KB | - |
| r3-head | head | 1442.2/s | 1413.8/s | 56550 | 1137 | 12124.2 ms | 13303.8 ms | 17301.5 ms | n/a | 18463.3 ms | 0.83 cores | kafka 53% · postgres 60% | 0.96 of 1 | load generator 0.96 | 1.39 | 67.1 KB | E4-list:read-http-429 832 · generator-overload 127 · E1:exception-ConnectException 88 · E4-state:exception-ConnectException 74 · E4-list:exception-ConnectException 16 |
| e1-base | base | 860.0/s | 860.0/s | 34400 | 0 | 2.1 ms | 7.5 ms | 18.0 ms | n/a | 43.8 ms | 0.84 cores | kafka 54% · postgres 58% | 0.44 of 1 | server 0.84 | 2.28 | 85.8 KB | - |
| e1-head | head | 860.0/s | 860.0/s | 34400 | 0 | 1.5 ms | 5.2 ms | 16.9 ms | n/a | 120.8 ms | 0.72 cores | kafka 61% · postgres 46% | 0.42 of 1 | server 0.72 | 2.09 | 67.8 KB | - |
| e2-head | head | 860.0/s | 860.0/s | 34400 | 0 | 1.5 ms | 5.2 ms | 20.1 ms | n/a | 131.1 ms | 0.74 cores | kafka 60% · postgres 46% | 0.43 of 1 | server 0.74 | 2.09 | 68.4 KB | - |
| e2-base | base | 860.0/s | 860.0/s | 34400 | 0 | 2.0 ms | 8.4 ms | 25.7 ms | n/a | 148.5 ms | 0.79 cores | kafka 55% · postgres 57% | 0.44 of 1 | server 0.79 | 2.22 | 85.6 KB | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed | E4-list p50 | E4-list p99 | E4-list failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 2.6 ms | 35.3 ms | 0 | 1.3 ms | 21.5 ms | 0 | 2.5 ms | 26.4 ms | 0 |
| r1-head | head | 11927.6 ms | 17301.5 ms | 0 | 11927.6 ms | 17301.5 ms | 0 | 11927.6 ms | 17301.5 ms | 435 |
| r2-head | head | 10551.3 ms | 15335.4 ms | 0 | 10551.3 ms | 15204.4 ms | 0 | 10420.2 ms | 11796.5 ms | 724 |
| r2-base | base | 2.6 ms | 25.0 ms | 0 | 1.3 ms | 14.8 ms | 0 | 2.5 ms | 20.1 ms | 0 |
| r3-base | base | 2.5 ms | 34.8 ms | 0 | 1.2 ms | 18.9 ms | 0 | 2.4 ms | 23.3 ms | 0 |
| r3-head | head | 12189.7 ms | 17563.6 ms | 88 | 12189.7 ms | 17170.4 ms | 74 | 12058.6 ms | 13434.9 ms | 848 |
| e1-base | base | 2.6 ms | 21.6 ms | 0 | 1.3 ms | 11.9 ms | 0 | 2.5 ms | 17.5 ms | 0 |
| e1-head | head | 1.6 ms | 20.7 ms | 0 | 1.1 ms | 11.3 ms | 0 | 2.1 ms | 15.2 ms | 0 |
| e2-head | head | 1.6 ms | 23.6 ms | 0 | 1.1 ms | 14.3 ms | 0 | 2.2 ms | 22.3 ms | 0 |
| e2-base | base | 2.5 ms | 30.5 ms | 0 | 1.2 ms | 17.8 ms | 0 | 2.3 ms | 23.6 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.633 | 5607.687 | 1407.316 | 595.474 | - | 179.315 | 0.606 | 0.792 |
| 2 | 1.656 | 4998.245 | 1388.770 | 745.636 | - | 145.232 | 0.611 | 0.830 |
| 3 | 1.644 | 5946.130 | 1464.048 | 634.615 | - | 197.058 | 0.614 | 0.782 |

Pairs at one rate for both arms, 860.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.693 | 0.685 | 0.936 | - | 2.760 | 0.915 | 0.790 |
| 2 | 1.000 | 0.737 | 0.610 | 0.781 | - | 0.883 | 0.939 | 0.799 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-pg@100k | r1-head | steady | **load generator saturated** | 0.95 of 1 cores: the run measured the generator |
| mixed-pg@100k | r1-head | steady | **error spike** | 1515 of 57687 failed: generator-overload 1080 · E4-list:read-http-429 435 |
| mixed-pg@100k | r2-head | steady | **load generator saturated** | 0.96 of 1 cores: the run measured the generator |
| mixed-pg@100k | r2-head | steady | **error spike** | 724 of 57687 failed: E4-list:read-http-429 724 |
| mixed-pg@100k | r3-head | steady | **load generator saturated** | 0.96 of 1 cores: the run measured the generator |
| mixed-pg@100k | r3-head | steady | **error spike** | 1137 of 57687 failed: E4-list:read-http-429 832 · generator-overload 127 · E1:exception-ConnectException 88 · E4-state:exception-ConnectException 74 · E4-list:exception-ConnectException 16 |


### mixed-pg@1k

Reads and writes together on PostgreSQL, as mixed-mongo.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1738.4/s | 32 | 586.7/s | 34% |
| head | 2240.5/s | 64 | 756.2/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1390.7/s | 843.6/s | 16873 | 10941 | 16580.6 ms | 20316.2 ms | 22020.1 ms | n/a | 22413.3 ms | 1.60 cores | kafka 72% · postgres 39% | 0.91 of 1 | server 1.60 | 3.20 | 50.9 KB | generator-overload 4532 · E1:exception-ConnectException 2742 · E4-state:exception-ConnectException 2179 · E4-list:read-http-429 944 · E4-list:exception-ConnectException 544 |
| probe2-base | base | 1043.0/s | 968.6/s | 19372 | 1488 | 6324.2 ms | 8847.4 ms | 10354.7 ms | n/a | 12058.6 ms | 1.62 cores | kafka 76% · postgres 42% | 0.91 of 1 | server 1.62 | 2.89 | 76.8 KB | E4-list:read-http-429 1488 |
| probe3-base | base | 782.3/s | 759.5/s | 15190 | 456 | 138.2 ms | 2572.3 ms | 4554.8 ms | n/a | 7405.6 ms | 1.58 cores | kafka 75% · postgres 45% | 0.78 of 1 | server 1.58 | 3.66 | 95.0 KB | E4-list:read-http-429 456 |
| probe4-base | base | 586.7/s | 585.2/s | 11704 | 30 | 5.8 ms | 366.6 ms | 1122.3 ms | n/a | 1622.0 ms | 1.59 cores | kafka 73% · postgres 37% | 0.62 of 1 | server 1.59 | 4.59 | 88.8 KB | E4-list:read-http-429 30 |
| probe1-head | head | 1792.4/s | 832.8/s | 16655 | 19192 | 18350.1 ms | 20578.3 ms | 20578.3 ms | n/a | 20629.5 ms | 1.46 cores | kafka 79% · postgres 36% | 0.94 of 1 | server 1.46 | 3.14 | 26.6 KB | generator-overload 13612 · E1:exception-ConnectException 2733 · E4-state:exception-ConnectException 2184 · E4-list:exception-ConnectException 538 · E4-list:read-http-429 125 |
| probe2-head | head | 1344.3/s | 1065.8/s | 21317 | 5569 | 16121.9 ms | 21233.7 ms | 21495.8 ms | n/a | 21758.0 ms | 1.50 cores | kafka 76% · postgres 43% | 0.93 of 1 | server 1.50 | 2.52 | 49.5 KB | generator-overload 2339 · E1:exception-ConnectException 1399 · E4-state:exception-ConnectException 1111 · E4-list:read-http-429 433 · E4-list:exception-ConnectException 287 |
| probe3-head | head | 1008.2/s | 980.3/s | 19605 | 559 | 6815.7 ms | 8159.2 ms | 9961.5 ms | n/a | 10551.3 ms | 1.58 cores | kafka 78% · postgres 40% | 0.91 of 1 | server 1.58 | 2.83 | 71.0 KB | E4-list:read-http-429 559 |
| probe4-head | head | 756.2/s | 740.5/s | 14810 | 314 | 270.3 ms | 2637.8 ms | 3129.3 ms | n/a | 3588.1 ms | 1.65 cores | kafka 76% · postgres 40% | 0.87 of 1 | server 1.65 | 3.79 | 84.9 KB | E4-list:read-http-429 314 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 586.7/s | 586.7/s | 23468 | 0 | 1.4 ms | 5.8 ms | 28.0 ms | n/a | 147.5 ms | 0.93 cores | kafka 64% · postgres 31% | 0.30 of 1 | server 0.93 | 3.20 | 79.8 KB | - |
| r1-head | head | 756.2/s | 756.2/s | 30248 | 0 | 3.0 ms | 26.9 ms | 92.2 ms | n/a | 243.7 ms | 1.51 cores | kafka 59% · postgres 46% | 0.41 of 1 | server 1.51 | 3.39 | 71.6 KB | - |
| r2-head | head | 756.2/s | 756.2/s | 30248 | 0 | 2.7 ms | 33.0 ms | 89.6 ms | n/a | 309.2 ms | 1.47 cores | kafka 62% · postgres 41% | 0.43 of 1 | server 1.47 | 3.30 | 71.1 KB | - |
| r2-base | base | 586.7/s | 586.7/s | 23468 | 0 | 1.6 ms | 7.4 ms | 24.2 ms | n/a | 74.2 ms | 1.11 cores | kafka 61% · postgres 33% | 0.34 of 1 | server 1.11 | 3.50 | 81.0 KB | - |
| r3-base | base | 586.7/s | 586.7/s | 23468 | 0 | 1.7 ms | 6.0 ms | 16.1 ms | n/a | 40.2 ms | 1.11 cores | kafka 62% · postgres 33% | 0.32 of 1 | server 1.11 | 3.52 | 79.9 KB | - |
| r3-head | head | 756.2/s | 756.2/s | 30248 | 0 | 3.0 ms | 42.2 ms | 116.7 ms | n/a | 325.6 ms | 1.52 cores | kafka 61% · postgres 42% | 0.44 of 1 | server 1.52 | 3.37 | 71.7 KB | - |
| e1-base | base | 586.7/s | 586.7/s | 23467 | 1 | 1.6 ms | 7.1 ms | 40.2 ms | n/a | 248.8 ms | 1.10 cores | kafka 64% · postgres 33% | 0.32 of 1 | server 1.10 | 3.54 | 80.3 KB | E4-list:read-http-429 1 |
| e1-head | head | 586.7/s | 586.7/s | 23468 | 0 | 1.6 ms | 7.7 ms | 39.9 ms | n/a | 179.2 ms | 1.13 cores | kafka 60% · postgres 32% | 0.33 of 1 | server 1.13 | 3.50 | 71.3 KB | - |
| e2-head | head | 586.7/s | 586.7/s | 23468 | 0 | 1.6 ms | 5.7 ms | 17.2 ms | n/a | 71.7 ms | 1.14 cores | kafka 65% · postgres 32% | 0.31 of 1 | server 1.14 | 3.59 | 70.8 KB | - |
| e2-base | base | 586.7/s | 586.7/s | 23468 | 0 | 1.8 ms | 9.6 ms | 30.7 ms | n/a | 73.7 ms | 1.13 cores | kafka 63% · postgres 34% | 0.35 of 1 | server 1.13 | 3.59 | 80.1 KB | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed | E4-list p50 | E4-list p99 | E4-list failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1.6 ms | 33.8 ms | 0 | 1.0 ms | 21.9 ms | 0 | 1.8 ms | 32.6 ms | 0 |
| r1-head | head | 3.3 ms | 119.3 ms | 0 | 2.0 ms | 51.2 ms | 0 | 4.4 ms | 59.1 ms | 0 |
| r2-head | head | 3.1 ms | 109.6 ms | 0 | 1.8 ms | 60.7 ms | 0 | 3.3 ms | 85.5 ms | 0 |
| r2-base | base | 1.9 ms | 27.0 ms | 0 | 1.2 ms | 19.2 ms | 0 | 2.2 ms | 25.5 ms | 0 |
| r3-base | base | 1.9 ms | 17.5 ms | 0 | 1.2 ms | 12.5 ms | 0 | 2.3 ms | 19.1 ms | 0 |
| r3-head | head | 3.5 ms | 144.4 ms | 0 | 2.0 ms | 83.5 ms | 0 | 3.6 ms | 96.8 ms | 0 |
| e1-base | base | 1.9 ms | 43.0 ms | 0 | 1.2 ms | 30.2 ms | 0 | 2.2 ms | 42.2 ms | 1 |
| e1-head | head | 1.9 ms | 55.8 ms | 0 | 1.2 ms | 22.7 ms | 0 | 2.1 ms | 33.0 ms | 0 |
| e2-head | head | 1.9 ms | 21.6 ms | 0 | 1.2 ms | 12.3 ms | 0 | 2.1 ms | 17.8 ms | 0 |
| e2-base | base | 2.0 ms | 32.3 ms | 0 | 1.3 ms | 26.9 ms | 0 | 2.5 ms | 36.6 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.289 | 2.068 | 4.667 | 3.288 | - | 1.653 | 1.057 | 0.898 |
| 2 | 1.289 | 1.641 | 4.449 | 3.704 | - | 4.166 | 0.945 | 0.878 |
| 3 | 1.289 | 1.771 | 6.985 | 7.267 | - | 8.102 | 0.957 | 0.898 |

Pairs at one rate for both arms, 586.7/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.005 | 1.076 | 0.994 | - | 0.720 | 0.988 | 0.887 |
| 2 | 1.000 | 0.919 | 0.590 | 0.558 | - | 0.972 | 1.001 | 0.884 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -37.6% (±25.0%) |
| mixed-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 3.0 ms, p99 92.2 ms |
| mixed-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -46.6% (±25.0%) |
| mixed-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 2.7 ms, p99 89.6 ms |
| mixed-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -31.5% (±25.0%) |
| mixed-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -33.6% (±25.0%) |
| mixed-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.6% (±25.0%) |
| mixed-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 3.0 ms, p99 116.7 ms |
| mixed-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -29.1% (±25.0%) |
| mixed-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -29.0% (±25.0%) |
| mixed-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -29.4% (±25.0%) |
| mixed-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -36.0% (±25.0%) |
| mixed-pg@1k | probe2-base | overload (probe) | **tail read the store** | gap=1 |


### large-mongo@100k

E1 with a large payload on MongoDB (D224 note ⑥): five events of about 2 KB per command. Head against baseline.

E1-large, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (56–165 s), then waited until the nodes were quiet again (4–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1037.9/s | 2048 | 622.7/s | 60% |
| head | 1419.6/s | 512 | 1135.7/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 830.3/s | 345.0/s | 6900 | 9706 | 8257.5 ms | 11337.7 ms | 11927.6 ms | n/a | 12845.1 ms | 1.22 cores | kafka 48% · mongo 111% | 0.87 of 1 | server 1.22 | 8.15 | 237.9 KB | http-429-wow.command.busy 7685 · http-504-wow.wait.timeout 1959 · http-503-wow.command.unknown 62 |
| probe2-base | base | 622.7/s | 622.0/s | 12440 | 13 | 4489.2 ms | 7405.6 ms | 8781.8 ms | n/a | 9371.6 ms | 1.21 cores | kafka 47% · mongo 122% | 0.69 of 1 | mongo 1.22 | 4.66 | 338.8 KB | http-504-wow.wait.timeout 13 |
| probe1-head | head | 1135.7/s | 1135.7/s | 22714 | 0 | 7569.4 ms | 8650.8 ms | 9764.9 ms | n/a | 10223.6 ms | 0.86 cores | kafka 53% · mongo 125% | 0.93 of 1 | mongo 1.25 | 2.33 | 221.7 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 622.7/s | 566.8/s | 22673 | 2234 | 5505.0 ms | 9044.0 ms | 9961.5 ms | n/a | 10747.9 ms | 1.30 cores | kafka 41% · mongo 123% | 0.40 of 1 | server 1.30 | 5.18 | 353.5 KB | http-504-wow.wait.timeout 1269 · http-429-wow.command.busy 883 · http-503-wow.command.unknown 80 · http-503-wow.storage.unavailable 2 |
| r1-head | head | 1135.7/s | 1135.7/s | 45428 | 0 | 272.4 ms | 3735.6 ms | 4259.8 ms | n/a | 4685.8 ms | 1.05 cores | kafka 51% · mongo 130% | 0.72 of 1 | mongo 1.30 | 2.52 | 240.9 KB | - |
| r2-head | head | 1135.7/s | 1029.0/s | 41162 | 4266 | 2801.7 ms | 7209.0 ms | 8781.8 ms | n/a | 9699.3 ms | 1.24 cores | kafka 50% · mongo 115% | 0.85 of 1 | server 1.24 | 2.81 | 239.3 KB | http-429-wow.command.busy 4266 |
| r2-base | base | 622.7/s | 528.6/s | 21144 | 3763 | 5963.8 ms | 9371.6 ms | 10027.0 ms | n/a | 10551.3 ms | 1.38 cores | kafka 40% · mongo 117% | 0.41 of 1 | server 1.38 | 5.59 | 364.9 KB | http-429-wow.command.busy 1844 · http-504-wow.wait.timeout 1823 · http-503-wow.command.unknown 96 |
| r3-base | base | 622.7/s | 515.4/s | 20615 | 4292 | 5668.9 ms | 9306.1 ms | 9961.5 ms | n/a | 10289.2 ms | 1.35 cores | kafka 40% · mongo 122% | 0.41 of 1 | server 1.35 | 5.75 | 364.4 KB | http-429-wow.command.busy 2071 · http-504-wow.wait.timeout 2026 · http-503-wow.command.unknown 193 · http-503-wow.storage.unavailable 2 |
| r3-head | head | 1135.7/s | 1031.8/s | 41272 | 4156 | 2752.5 ms | 8093.7 ms | 9306.1 ms | n/a | 10354.7 ms | 1.20 cores | kafka 53% · mongo 117% | 0.85 of 1 | server 1.20 | 2.81 | 241.9 KB | http-429-wow.command.busy 4156 |
| e1-base | base | 622.7/s | 470.3/s | 18812 | 6095 | 6291.5 ms | 9502.7 ms | 10092.5 ms | n/a | 10485.8 ms | 1.32 cores | kafka 40% · mongo 120% | 0.46 of 1 | server 1.32 | 6.22 | 352.2 KB | http-429-wow.command.busy 3561 · http-504-wow.wait.timeout 2490 · http-503-wow.command.unknown 42 · http-503-wow.storage.unavailable 2 |
| e1-head | head | 622.7/s | 622.7/s | 24907 | 0 | 8.3 ms | 81.4 ms | 176.1 ms | n/a | 360.4 ms | 0.76 cores | kafka 51% · mongo 134% | 0.34 of 1 | mongo 1.34 | 4.19 | 256.7 KB | - |
| e2-head | head | 622.7/s | 622.7/s | 24907 | 0 | 7.1 ms | 55.3 ms | 122.9 ms | n/a | 237.6 ms | 0.78 cores | kafka 53% · mongo 129% | 0.34 of 1 | mongo 1.29 | 4.19 | 258.3 KB | - |
| e2-base | base | 622.7/s | 416.5/s | 16660 | 8247 | 6815.7 ms | 9764.9 ms | 10289.2 ms | n/a | 10682.4 ms | 1.31 cores | kafka 42% · mongo 121% | 0.41 of 1 | server 1.31 | 7.06 | 347.9 KB | http-429-wow.command.busy 4656 · http-504-wow.wait.timeout 3507 · http-503-wow.command.unknown 82 · http-503-wow.storage.unavailable 2 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 2.004 | 0.049 | 0.413 | 0.428 | - | 0.436 | 0.487 | 0.681 |
| 2 | 1.947 | 0.470 | 0.769 | 0.876 | - | 0.919 | 0.504 | 0.656 |
| 3 | 2.002 | 0.486 | 0.870 | 0.934 | - | 1.006 | 0.488 | 0.664 |

Pairs at one rate for both arms, 622.7/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.324 | 0.001 | 0.009 | 0.017 | - | 0.034 | 0.674 | 0.729 |
| 2 | 1.495 | 0.001 | 0.006 | 0.012 | - | 0.022 | 0.593 | 0.742 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: throughput -12.6% (±5.0%) |
| large-mongo@100k | r1-base | steady | **error spike** | 2234 of 24907 failed: http-504-wow.wait.timeout 1269 · http-429-wow.command.busy 883 · http-503-wow.command.unknown 80 · http-503-wow.storage.unavailable 2 |
| large-mongo@100k | r1-base | steady | **throughput dip** | 1 s below half the rate (lowest 282/s) |
| large-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -94.9% (±25.0%) |
| large-mongo@100k | r1-head | steady | **p99 > 10× p50** | p50 272.4 ms, p99 4259.8 ms |
| large-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: throughput +23.1% (±5.0%), p50 -55.5% (±25.0%) |
| large-mongo@100k | r2-head | steady | **error spike** | 4266 of 45428 failed: http-429-wow.command.busy 4266 |
| large-mongo@100k | r2-head | steady | **throughput dip** | 3 s below half the rate (lowest 21/s) |
| large-mongo@100k | r2-base | steady | **error spike** | 3763 of 24907 failed: http-429-wow.command.busy 1844 · http-504-wow.wait.timeout 1823 · http-503-wow.command.unknown 96 |
| large-mongo@100k | r2-base | steady | **throughput dip** | 2 s below half the rate (lowest 176/s) |
| large-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: throughput +5.1% (±5.0%) |
| large-mongo@100k | r3-base | steady | **error spike** | 4292 of 24907 failed: http-429-wow.command.busy 2071 · http-504-wow.wait.timeout 2026 · http-503-wow.command.unknown 193 · http-503-wow.storage.unavailable 2 |
| large-mongo@100k | r3-base | steady | **throughput dip** | 1 s below half the rate (lowest 262/s) |
| large-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: throughput +22.4% (±5.0%), p50 -64.3% (±25.0%) |
| large-mongo@100k | r3-head | steady | **error spike** | 4156 of 45428 failed: http-429-wow.command.busy 4156 |
| large-mongo@100k | r3-head | steady | **throughput dip** | 3 s below half the rate (lowest 63/s) |
| large-mongo@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: throughput +48.0% (±5.0%) |
| large-mongo@100k | e1-base | steady | **error spike** | 6095 of 24907 failed: http-429-wow.command.busy 3561 · http-504-wow.wait.timeout 2490 · http-503-wow.command.unknown 42 · http-503-wow.storage.unavailable 2 |
| large-mongo@100k | e1-base | steady | **throughput dip** | 6 s below half the rate (lowest 213/s) |
| large-mongo@100k | e1-head | steady | **p99 > 10× p50** | p50 8.3 ms, p99 176.1 ms |
| large-mongo@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -25.4% (±25.0%) |
| large-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 7.1 ms, p99 122.9 ms |
| large-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: throughput +10.5% (±5.0%) |
| large-mongo@100k | e2-base | steady | **error spike** | 8247 of 24907 failed: http-429-wow.command.busy 4656 · http-504-wow.wait.timeout 3507 · http-503-wow.command.unknown 82 · http-503-wow.storage.unavailable 2 |
| large-mongo@100k | e2-base | steady | **throughput dip** | 7 s below half the rate (lowest 164/s) |


### large-mongo@1k

E1 with a large payload on MongoDB (D224 note ⑥): five events of about 2 KB per command. Head against baseline.

E1-large, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1158.4/s | 2048 | 391.0/s | 34% |
| head | 1740.6/s | 2048 | 587.5/s | 34% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 926.7/s | 120.7/s | 2414 | 16120 | 13107.2 ms | 16908.3 ms | 17694.7 ms | n/a | 18811.5 ms | 1.66 cores | kafka 67% · mongo 60% | 0.91 of 1 | server 1.66 | 24.30 | 138.8 KB | http-429-wow.command.busy 12263 · http-504-wow.wait.timeout 3857 |
| probe2-base | base | 695.0/s | 238.4/s | 4769 | 9131 | 9044.0 ms | 12320.8 ms | 12910.6 ms | n/a | 14015.6 ms | 1.56 cores | kafka 72% · mongo 61% | 0.85 of 1 | server 1.56 | 12.11 | 181.0 KB | http-429-wow.command.busy 6795 · http-504-wow.wait.timeout 2336 |
| probe3-base | base | 521.3/s | 362.4/s | 7248 | 3177 | 7569.4 ms | 11141.1 ms | 11993.1 ms | n/a | 13303.8 ms | 1.54 cores | kafka 74% · mongo 65% | 0.75 of 1 | server 1.54 | 8.09 | 254.6 KB | http-504-wow.wait.timeout 1910 · http-429-wow.command.busy 1267 |
| probe4-base | base | 391.0/s | 388.4/s | 7767 | 53 | 2916.4 ms | 8028.2 ms | 9895.9 ms | n/a | 11337.7 ms | 1.38 cores | kafka 76% · mongo 75% | 0.64 of 1 | server 1.38 | 7.45 | 345.8 KB | http-504-wow.wait.timeout 53 |
| probe1-head | head | 1392.5/s | 706.5/s | 14130 | 13720 | 17301.5 ms | 24117.2 ms | 24379.4 ms | n/a | 25236.9 ms | 1.48 cores | kafka 81% · mongo 57% | 0.92 of 1 | server 1.48 | 4.05 | 105.6 KB | generator-overload 8228 · exception-ConnectException 5492 |
| probe2-head | head | 1044.4/s | 530.5/s | 10611 | 10277 | 14942.2 ms | 18481.2 ms | 19529.7 ms | n/a | 20840.4 ms | 1.69 cores | kafka 74% · mongo 47% | 0.94 of 1 | server 1.69 | 5.48 | 142.7 KB | http-429-wow.command.busy 9303 · http-504-wow.wait.timeout 974 |
| probe3-head | head | 783.3/s | 577.1/s | 11542 | 4124 | 7831.6 ms | 11403.3 ms | 12517.4 ms | n/a | 14680.1 ms | 1.65 cores | kafka 76% · mongo 51% | 0.92 of 1 | server 1.65 | 5.07 | 201.5 KB | http-429-wow.command.busy 4051 · http-504-wow.wait.timeout 73 |
| probe4-head | head | 587.5/s | 582.5/s | 11651 | 99 | 4227.1 ms | 6979.6 ms | 8060.9 ms | n/a | 9764.9 ms | 1.52 cores | kafka 84% · mongo 56% | 0.81 of 1 | server 1.52 | 5.02 | 280.4 KB | http-429-wow.command.busy 99 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 391.0/s | 391.0/s | 15640 | 0 | 315.4 ms | 1024.0 ms | 1458.2 ms | n/a | 2703.4 ms | 1.37 cores | kafka 52% · mongo 104% | 0.33 of 1 | server 1.37 | 7.47 | 354.0 KB | - |
| r1-head | head | 587.5/s | 587.5/s | 23500 | 0 | 58.9 ms | 512.0 ms | 958.5 ms | n/a | 1523.7 ms | 1.42 cores | kafka 55% · mongo 94% | 0.40 of 1 | server 1.42 | 4.97 | 251.3 KB | - |
| r2-head | head | 587.5/s | 587.5/s | 23500 | 0 | 82.4 ms | 1826.8 ms | 2949.1 ms | n/a | 3817.5 ms | 1.46 cores | kafka 58% · mongo 89% | 0.46 of 1 | server 1.46 | 4.99 | 263.5 KB | - |
| r2-base | base | 391.0/s | 391.0/s | 15640 | 0 | 340.0 ms | 1179.6 ms | 1695.7 ms | n/a | 2768.9 ms | 1.33 cores | kafka 52% · mongo 105% | 0.38 of 1 | server 1.33 | 7.42 | 346.7 KB | - |
| r3-base | base | 391.0/s | 391.0/s | 15640 | 0 | 270.3 ms | 1097.7 ms | 1720.3 ms | n/a | 3211.3 ms | 1.39 cores | kafka 49% · mongo 106% | 0.33 of 1 | server 1.39 | 7.54 | 365.0 KB | - |
| r3-head | head | 587.5/s | 587.5/s | 23500 | 0 | 91.6 ms | 806.9 ms | 1228.8 ms | n/a | 1998.8 ms | 1.42 cores | kafka 60% · mongo 92% | 0.44 of 1 | server 1.42 | 5.02 | 246.4 KB | - |
| e1-base | base | 391.0/s | 391.0/s | 15640 | 0 | 593.9 ms | 3162.1 ms | 5308.4 ms | n/a | 8585.2 ms | 1.39 cores | kafka 53% · mongo 100% | 0.38 of 1 | server 1.39 | 7.49 | 348.5 KB | - |
| e1-head | head | 391.0/s | 391.0/s | 15640 | 0 | 21.4 ms | 233.5 ms | 380.9 ms | n/a | 577.5 ms | 1.39 cores | kafka 60% · mongo 91% | 0.34 of 1 | server 1.39 | 7.43 | 266.4 KB | - |
| e2-head | head | 391.0/s | 391.0/s | 15640 | 0 | 19.2 ms | 329.7 ms | 557.1 ms | n/a | 786.4 ms | 1.42 cores | kafka 57% · mongo 90% | 0.34 of 1 | server 1.42 | 7.41 | 265.7 KB | - |
| e2-base | base | 391.0/s | 391.0/s | 15640 | 0 | 250.9 ms | 1065.0 ms | 1564.7 ms | n/a | 2588.7 ms | 1.38 cores | kafka 49% · mongo 107% | 0.32 of 1 | server 1.38 | 7.51 | 361.3 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.503 | 0.187 | 0.500 | 0.657 | - | 0.564 | 0.665 | 0.710 |
| 2 | 1.503 | 0.242 | 1.549 | 1.739 | - | 1.379 | 0.673 | 0.760 |
| 3 | 1.503 | 0.339 | 0.735 | 0.714 | - | 0.622 | 0.665 | 0.675 |

Pairs at one rate for both arms, 391.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.036 | 0.074 | 0.072 | - | 0.067 | 0.993 | 0.764 |
| 2 | 1.000 | 0.077 | 0.310 | 0.356 | - | 0.304 | 0.986 | 0.735 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -34.2% (±25.0%) |
| large-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -30.7% (±25.0%) |
| large-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 58.9 ms, p99 958.5 ms |
| large-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -70.1% (±25.0%) |
| large-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 82.4 ms, p99 2949.1 ms |
| large-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -31.3% (±25.0%) |
| large-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -50.0% (±25.0%) |
| large-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -63.4% (±25.0%) |
| large-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 91.6 ms, p99 1228.8 ms |
| large-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -60.6% (±25.0%) |
| large-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -41.7% (±25.0%) |
| large-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 21.4 ms, p99 380.9 ms |
| large-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -29.9% (±25.0%) |
| large-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 19.2 ms, p99 557.1 ms |
| large-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -63.7% (±25.0%) |


### large-pg@100k

E1 with a large payload on PostgreSQL, as large-mongo.

E1-large, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (52–95 s), then waited until the nodes were quiet again (4–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1126.1/s | 64 | 675.7/s | 60% |
| head | 1355.8/s | 128 | 1084.6/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 900.9/s | 803.0/s | 16059 | 1959 | 12255.2 ms | 15990.8 ms | 17170.4 ms | n/a | 18743.3 ms | 1.08 cores | kafka 59% · postgres 100% | 0.91 of 1 | server 1.08 | 3.32 | 212.7 KB | http-429-wow.command.busy 1959 |
| probe2-base | base | 675.7/s | 675.7/s | 13514 | 0 | 1105.9 ms | 3719.2 ms | 3915.8 ms | n/a | 4358.1 ms | 0.97 cores | kafka 58% · postgres 104% | 0.89 of 1 | postgres 1.04 | 3.83 | 298.7 KB | - |
| probe1-head | head | 1084.6/s | 1084.5/s | 21691 | 0 | 6094.8 ms | 8585.2 ms | 8847.4 ms | n/a | 9367.9 ms | 1.01 cores | kafka 54% · postgres 102% | 0.95 of 1 | postgres 1.02 | 2.37 | 249.9 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 675.7/s | 675.7/s | 27027 | 0 | 9.5 ms | 209.9 ms | 505.9 ms | n/a | 757.8 ms | 0.98 cores | kafka 54% · postgres 98% | 0.38 of 1 | postgres 0.98 | 3.70 | 271.7 KB | - |
| r1-head | head | 1084.6/s | 1084.6/s | 43384 | 0 | 178.2 ms | 2375.7 ms | 2949.1 ms | n/a | 3440.6 ms | 0.96 cores | kafka 57% · postgres 111% | 0.68 of 1 | postgres 1.11 | 2.44 | 238.4 KB | - |
| r2-head | head | 1084.6/s | 1084.6/s | 43384 | 0 | 185.3 ms | 1499.1 ms | 1851.4 ms | n/a | 2228.2 ms | 0.91 cores | kafka 51% · postgres 107% | 0.62 of 1 | postgres 1.07 | 2.30 | 230.4 KB | - |
| r2-base | base | 675.7/s | 675.7/s | 27027 | 0 | 11.2 ms | 294.9 ms | 651.3 ms | n/a | 999.4 ms | 0.97 cores | kafka 54% · postgres 98% | 0.40 of 1 | postgres 0.98 | 3.69 | 270.9 KB | - |
| r3-base | base | 675.7/s | 675.7/s | 27027 | 0 | 9.0 ms | 226.3 ms | 667.6 ms | n/a | 938.0 ms | 0.96 cores | kafka 55% · postgres 98% | 0.40 of 1 | postgres 0.98 | 3.69 | 271.3 KB | - |
| r3-head | head | 1084.6/s | 1056.5/s | 42260 | 1124 | 2457.6 ms | 6357.0 ms | 6848.5 ms | n/a | 7405.6 ms | 1.12 cores | kafka 55% · postgres 103% | 0.77 of 1 | server 1.12 | 2.55 | 252.8 KB | http-429-wow.command.busy 1124 |
| e1-base | base | 675.7/s | 675.7/s | 27027 | 0 | 9.1 ms | 247.8 ms | 626.7 ms | n/a | 1073.2 ms | 0.96 cores | kafka 54% · postgres 95% | 0.39 of 1 | server 0.96 | 3.64 | 272.2 KB | - |
| e1-head | head | 675.7/s | 675.7/s | 27027 | 0 | 3.1 ms | 35.8 ms | 280.6 ms | n/a | 602.1 ms | 0.82 cores | kafka 59% · postgres 78% | 0.37 of 1 | server 0.82 | 3.24 | 235.7 KB | - |
| e2-head | head | 675.7/s | 675.7/s | 27027 | 0 | 3.5 ms | 125.4 ms | 409.6 ms | n/a | 618.5 ms | 0.88 cores | kafka 57% · postgres 80% | 0.37 of 1 | server 0.88 | 3.33 | 234.4 KB | - |
| e2-base | base | 675.7/s | 675.7/s | 27027 | 0 | 10.7 ms | 329.7 ms | 692.2 ms | n/a | 1089.5 ms | 0.96 cores | kafka 54% · postgres 96% | 0.36 of 1 | server 0.96 | 3.63 | 270.4 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.605 | 18.813 | 11.317 | 5.830 | - | 4.541 | 0.660 | 0.877 |
| 2 | 1.605 | 16.550 | 5.083 | 2.843 | - | 2.230 | 0.623 | 0.851 |
| 3 | 1.564 | 272.370 | 28.091 | 10.258 | - | 7.895 | 0.691 | 0.932 |

Pairs at one rate for both arms, 675.7/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.343 | 0.145 | 0.448 | - | 0.561 | 0.891 | 0.866 |
| 2 | 1.000 | 0.325 | 0.380 | 0.592 | - | 0.568 | 0.918 | 0.867 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -38.3% (±25.0%) |
| large-pg@100k | r1-base | steady | **p99 > 10× p50** | p50 9.5 ms, p99 505.9 ms |
| large-pg@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -89.2% (±25.0%) |
| large-pg@100k | r1-head | steady | **p99 > 10× p50** | p50 178.2 ms, p99 2949.1 ms |
| large-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -88.3% (±25.0%) |
| large-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +259.5% (±25.0%) |
| large-pg@100k | r2-base | steady | **p99 > 10× p50** | p50 11.2 ms, p99 651.3 ms |
| large-pg@100k | r3-base | steady | **p99 > 10× p50** | p50 9.0 ms, p99 667.6 ms |
| large-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: throughput +5.5% (±5.0%), p50 -82.7% (±25.0%) |
| large-pg@100k | r3-head | steady | **error spike** | 1124 of 43384 failed: http-429-wow.command.busy 1124 |
| large-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +30.9% (±25.0%) |
| large-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 9.1 ms, p99 626.7 ms |
| large-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -31.3% (±25.0%) |
| large-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 3.1 ms, p99 280.6 ms |
| large-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -43.8% (±25.0%) |
| large-pg@100k | e2-head | steady | **p99 > 10× p50** | p50 3.5 ms, p99 409.6 ms |
| large-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +42.3% (±25.0%) |
| large-pg@100k | e2-base | steady | **p99 > 10× p50** | p50 10.7 ms, p99 692.2 ms |


### large-pg@1k

E1 with a large payload on PostgreSQL, as large-mongo.

E1-large, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1143.9/s | 64 | 386.1/s | 34% |
| head | 1288.1/s | 512 | 579.7/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 915.1/s | 176.2/s | 3523 | 14778 | 13434.9 ms | 16515.1 ms | 17694.7 ms | n/a | 20124.1 ms | 1.72 cores | kafka 76% · postgres 41% | 0.91 of 1 | server 1.72 | 16.41 | 138.3 KB | http-429-wow.command.busy 11390 · http-504-wow.wait.timeout 3388 |
| probe2-base | base | 686.3/s | 297.0/s | 5940 | 7786 | 8126.5 ms | 12189.7 ms | 13238.3 ms | n/a | 13893.6 ms | 1.59 cores | kafka 75% · postgres 53% | 0.81 of 1 | server 1.59 | 9.65 | 205.2 KB | http-429-wow.command.busy 5446 · http-504-wow.wait.timeout 2340 |
| probe3-base | base | 514.7/s | 459.8/s | 9196 | 1097 | 4194.3 ms | 9961.5 ms | 10944.5 ms | n/a | 12124.2 ms | 1.52 cores | kafka 78% · postgres 62% | 0.70 of 1 | server 1.52 | 6.34 | 292.5 KB | http-504-wow.wait.timeout 1097 |
| probe4-base | base | 386.1/s | 385.9/s | 7719 | 3 | 1204.2 ms | 4784.1 ms | 7503.9 ms | n/a | 10747.9 ms | 1.49 cores | kafka 79% · postgres 60% | 0.65 of 1 | server 1.49 | 7.48 | 329.1 KB | http-504-wow.wait.timeout 3 |
| probe1-head | head | 1030.5/s | 474.1/s | 9483 | 11127 | 14549.0 ms | 18612.2 ms | 19791.9 ms | n/a | 21495.8 ms | 1.70 cores | kafka 74% · postgres 43% | 0.93 of 1 | server 1.70 | 6.07 | 148.5 KB | http-429-wow.command.busy 9932 · http-504-wow.wait.timeout 1195 |
| probe2-head | head | 772.9/s | 540.5/s | 10810 | 4648 | 7602.2 ms | 11665.4 ms | 12779.5 ms | n/a | 13631.5 ms | 1.59 cores | kafka 81% · postgres 52% | 0.86 of 1 | server 1.59 | 5.40 | 205.1 KB | http-429-wow.command.busy 4395 · http-504-wow.wait.timeout 253 |
| probe3-head | head | 579.7/s | 579.6/s | 11593 | 1 | 3833.9 ms | 6815.7 ms | 8060.9 ms | n/a | 10289.2 ms | 1.51 cores | kafka 83% · postgres 58% | 0.70 of 1 | server 1.51 | 5.04 | 294.8 KB | http-504-wow.wait.timeout 1 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 386.1/s | 386.1/s | 15444 | 0 | 3.2 ms | 44.0 ms | 112.6 ms | n/a | 261.1 ms | 1.23 cores | kafka 59% · postgres 51% | 0.28 of 1 | server 1.23 | 6.03 | 262.1 KB | - |
| r1-head | head | 579.7/s | 579.7/s | 23188 | 0 | 58.1 ms | 733.2 ms | 1540.1 ms | n/a | 2424.8 ms | 1.54 cores | kafka 55% · postgres 80% | 0.39 of 1 | server 1.54 | 4.98 | 243.8 KB | - |
| r2-head | head | 579.7/s | 579.7/s | 23188 | 0 | 58.9 ms | 536.6 ms | 974.8 ms | n/a | 1679.4 ms | 1.52 cores | kafka 53% · postgres 81% | 0.38 of 1 | server 1.52 | 4.93 | 245.6 KB | - |
| r2-base | base | 386.1/s | 386.1/s | 15444 | 0 | 3.3 ms | 24.6 ms | 69.1 ms | n/a | 146.4 ms | 1.21 cores | kafka 60% · postgres 52% | 0.28 of 1 | server 1.21 | 6.01 | 261.8 KB | - |
| r3-base | base | 386.1/s | 386.1/s | 15444 | 0 | 3.0 ms | 89.1 ms | 193.5 ms | n/a | 634.9 ms | 1.18 cores | kafka 62% · postgres 51% | 0.26 of 1 | server 1.18 | 5.96 | 261.8 KB | - |
| r3-head | head | 579.7/s | 579.7/s | 23188 | 0 | 64.5 ms | 462.8 ms | 806.9 ms | n/a | 1400.8 ms | 1.53 cores | kafka 53% · postgres 81% | 0.38 of 1 | server 1.53 | 4.94 | 242.2 KB | - |
| e1-base | base | 386.1/s | 386.1/s | 15444 | 0 | 2.8 ms | 23.2 ms | 72.7 ms | n/a | 176.1 ms | 1.08 cores | kafka 61% · postgres 50% | 0.30 of 1 | server 1.08 | 5.65 | 260.3 KB | - |
| e1-head | head | 386.1/s | 386.1/s | 15444 | 0 | 2.9 ms | 37.9 ms | 106.5 ms | n/a | 284.7 ms | 1.12 cores | kafka 63% · postgres 51% | 0.28 of 1 | server 1.12 | 5.85 | 240.1 KB | - |
| e2-head | head | 386.1/s | 386.1/s | 15444 | 0 | 3.2 ms | 29.8 ms | 94.2 ms | n/a | 331.8 ms | 1.19 cores | kafka 62% · postgres 52% | 0.30 of 1 | server 1.19 | 6.03 | 240.5 KB | - |
| e2-base | base | 386.1/s | 386.1/s | 15444 | 0 | 3.2 ms | 56.8 ms | 178.2 ms | n/a | 348.2 ms | 1.17 cores | kafka 59% · postgres 50% | 0.30 of 1 | server 1.17 | 5.87 | 260.6 KB | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.501 | 18.257 | 16.652 | 13.673 | - | 9.286 | 0.826 | 0.930 |
| 2 | 1.501 | 17.869 | 21.834 | 14.104 | - | 11.469 | 0.820 | 0.938 |
| 3 | 1.501 | 21.568 | 5.195 | 4.169 | - | 2.206 | 0.829 | 0.925 |

Pairs at one rate for both arms, 386.1/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.022 | 1.635 | 1.465 | - | 1.616 | 1.035 | 0.922 |
| 2 | 1.000 | 1.005 | 0.525 | 0.529 | - | 0.953 | 1.028 | 0.923 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -46.5% (±25.0%) |
| large-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 3.2 ms, p99 112.6 ms |
| large-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -82.2% (±25.0%) |
| large-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 58.1 ms, p99 1540.1 ms |
| large-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -74.9% (±25.0%) |
| large-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 58.9 ms, p99 974.8 ms |
| large-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -44.1% (±25.0%) |
| large-pg@1k | r2-base | steady | **p99 > 10× p50** | p50 3.3 ms, p99 69.1 ms |
| large-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -46.8% (±25.0%) |
| large-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 3.0 ms, p99 193.5 ms |
| large-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -73.0% (±25.0%) |
| large-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 64.5 ms, p99 806.9 ms |
| large-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -43.8% (±25.0%) |
| large-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 2.8 ms, p99 72.7 ms |
| large-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -40.1% (±25.0%) |
| large-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 2.9 ms, p99 106.5 ms |
| large-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -48.6% (±25.0%) |
| large-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 3.2 ms, p99 94.2 ms |
| large-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -62.8% (±25.0%) |
| large-pg@1k | e2-base | steady | **p99 > 10× p50** | p50 3.2 ms, p99 178.2 ms |
| large-pg@1k | probe1-base | overload (probe) | **tail read the store** | gap=24 |
| large-pg@1k | probe2-base | overload (probe) | **tail read the store** | gap=9 |
| large-pg@1k | probe3-base | overload (probe) | **tail read the store** | gap=3 |
| large-pg@1k | probe4-base | overload (probe) | **tail read the store** | gap=3 |



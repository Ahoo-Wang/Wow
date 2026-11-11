# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `main` a7c372ad3ef808f479776a6183247c39f46189fc; V10 baseline: `v10.0.0-beta.1` 22f8ac19cb50b73d90613d7d401257600b3a914f; harness and load generator from head.

Tier: L3; coverage: e1-mongo-uniform@100k, e1-mongo-uniform@1k, e1-mongo-zipf@100k, e1-mongo-zipf@1k, e1-pg-uniform@100k, e1-pg-uniform@1k, e2-mongo@100k, e2-mongo@1k, e3-mongo@100k, e3-mongo@1k, e3-pg@100k, e3-pg@1k, mixed-mongo@100k, mixed-mongo@1k, mixed-pg@100k, mixed-pg@1k, large-mongo@100k, large-mongo@1k, large-pg@100k, large-pg@1k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +25.1% (±19.9%) |
| e1-mongo-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +103.5% (±19.9%) |
| e1-mongo-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +24.1% (±19.9%) |
| e1-mongo-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +40.2% (±19.9%) |
| e1-mongo-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +62.1% (±19.9%) |
| e1-mongo-uniform@100k | e2-head | steady | **error spike** | 1241 of 100124 failed: generator-overload 1241; generator-overload: 2000 in flight at most (per-target ceiling) |
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
| e1-mongo-zipf@100k | r1-base | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r1-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r2-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@100k | r2-base | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r3-base | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +27.5% (±27.4%) |
| e1-mongo-zipf@100k | r3-head | steady | **tail read the store** | overflow=4 |
| e1-mongo-zipf@100k | e1-base | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | e1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +51.9% (±27.4%) |
| e1-mongo-zipf@100k | e1-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | e1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -49.0% (±27.4%) |
| e1-mongo-zipf@100k | e1-head | steady | **p99 > 10× p50** | p50 75.3 ms, p99 790.5 ms |
| e1-mongo-zipf@100k | e2-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | e2-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@100k | probe1-base | overload (probe) | **tail read the store** | overflow=1 |
| e1-mongo-zipf@100k | probe1-head | overload (probe) | **tail read the store** | overflow=1 |
| e1-mongo-zipf@1k | r1-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -84.2% (±25.0%) |
| e1-mongo-zipf@1k | r1-base | steady | **p99 > 10× p50** | p50 86.0 ms, p99 4390.9 ms |
| e1-mongo-zipf@1k | r1-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -81.5% (±25.0%) |
| e1-mongo-zipf@1k | r1-head | steady | **p99 > 10× p50** | p50 120.8 ms, p99 6127.6 ms |
| e1-mongo-zipf@1k | r2-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -74.5% (±25.0%) |
| e1-mongo-zipf@1k | r2-head | steady | **p99 > 10× p50** | p50 136.2 ms, p99 6750.2 ms |
| e1-mongo-zipf@1k | r2-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -82.8% (±25.0%) |
| e1-mongo-zipf@1k | r2-base | steady | **p99 > 10× p50** | p50 142.3 ms, p99 6553.6 ms |
| e1-mongo-zipf@1k | r3-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -85.3% (±25.0%) |
| e1-mongo-zipf@1k | r3-base | steady | **p99 > 10× p50** | p50 58.6 ms, p99 4620.3 ms |
| e1-mongo-zipf@1k | r3-head | steady | **tail read the store** | overflow=1 |
| e1-mongo-zipf@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -79.4% (±25.0%) |
| e1-mongo-zipf@1k | r3-head | steady | **p99 > 10× p50** | p50 139.3 ms, p99 6651.9 ms |
| e1-mongo-zipf@1k | e1-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -68.4% (±25.0%) |
| e1-mongo-zipf@1k | e1-base | steady | **p99 > 10× p50** | p50 122.9 ms, p99 5898.2 ms |
| e1-mongo-zipf@1k | e1-head | steady | **tail read the store** | overflow=1 |
| e1-mongo-zipf@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.1% (±25.0%) |
| e1-mongo-zipf@1k | e1-head | steady | **p99 > 10× p50** | p50 199.7 ms, p99 8126.5 ms |
| e1-mongo-zipf@1k | e2-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.6% (±25.0%) |
| e1-mongo-zipf@1k | e2-head | steady | **p99 > 10× p50** | p50 140.3 ms, p99 6979.6 ms |
| e1-mongo-zipf@1k | e2-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -79.0% (±25.0%) |
| e1-mongo-zipf@1k | e2-base | steady | **p99 > 10× p50** | p50 75.3 ms, p99 4685.8 ms |
| e1-pg-uniform@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +47.6% (±46.2%) |
| e1-pg-uniform@100k | r3-head | steady | **p99 > 10× p50** | p50 25.3 ms, p99 305.2 ms |
| e1-pg-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -53.5% (±46.2%) |
| e1-pg-uniform@100k | e1-base | steady | **p99 > 10× p50** | p50 17.5 ms, p99 688.1 ms |
| e1-pg-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +104.4% (±46.2%) |
| e1-pg-uniform@100k | e2-base | steady | **p99 > 10× p50** | p50 19.5 ms, p99 278.5 ms |
| e1-pg-uniform@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -73.4% (±25.0%) |
| e1-pg-uniform@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -65.7% (±25.0%) |
| e1-pg-uniform@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: throughput +8.6% (±5.0%), p50 -72.3% (±25.0%) |
| e1-pg-uniform@1k | r2-head | steady | **error spike** | 1162 of 29236 failed: generator-overload 851 · http-504-wow.wait.timeout 311; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-pg-uniform@1k | r2-head | steady | **throughput dip** | 1 s below half the rate (lowest 224/s) |
| e1-pg-uniform@1k | r2-head | steady | **p99 > 10× p50** | p50 585.7 ms, p99 5996.5 ms |
| e1-pg-uniform@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -87.1% (±25.0%) |
| e1-pg-uniform@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -88.4% (±25.0%) |
| e1-pg-uniform@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -80.7% (±25.0%) |
| e1-pg-uniform@1k | r3-head | steady | **error spike** | 683 of 29236 failed: generator-overload 683; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-pg-uniform@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -81.6% (±25.0%) |
| e1-pg-uniform@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: throughput +7.8% (±5.0%), p50 -54.8% (±25.0%) |
| e1-pg-uniform@1k | e1-head | steady | **error spike** | 1003 of 27807 failed: http-504-wow.wait.timeout 732 · generator-overload 271; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-pg-uniform@1k | e1-head | steady | **p99 > 10× p50** | p50 553.0 ms, p99 6225.9 ms |
| e1-pg-uniform@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: throughput +5.4% (±5.0%), p50 -81.7% (±25.0%) |
| e1-pg-uniform@1k | e2-head | steady | **error spike** | 713 of 27807 failed: generator-overload 713; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-pg-uniform@1k | e2-head | steady | **throughput dip** | 1 s below half the rate (lowest 206/s) |
| e1-pg-uniform@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -76.2% (±25.0%) |
| e1-pg-uniform@1k | e2-base | steady | **error spike** | 443 of 27807 failed: generator-overload 443; generator-overload: 2000 in flight at most (per-target ceiling) |
| e2-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -23.9% (±18.0%) |
| e2-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -26.3% (±18.0%) |
| e2-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -94.4% (±25.0%) |
| e2-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -64.9% (±25.0%) |
| e2-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 109.1 ms, p99 2424.8 ms |
| e2-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -63.0% (±25.0%) |
| e2-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 141.3 ms, p99 2474.0 ms |
| e2-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -59.6% (±25.0%) |
| e2-mongo@1k | r2-base | steady | **p99 > 10× p50** | p50 92.2 ms, p99 1122.3 ms |
| e2-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -68.9% (±25.0%) |
| e2-mongo@1k | r3-base | steady | **p99 > 10× p50** | p50 142.3 ms, p99 2424.8 ms |
| e2-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -86.9% (±25.0%) |
| e2-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 147.5 ms, p99 3162.1 ms |
| e2-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -48.6% (±25.0%) |
| e2-mongo@1k | e1-base | steady | **p99 > 10× p50** | p50 102.9 ms, p99 1409.0 ms |
| e2-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -72.1% (±25.0%) |
| e2-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 122.9 ms, p99 2785.3 ms |
| e2-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -84.3% (±25.0%) |
| e2-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 131.1 ms, p99 2965.5 ms |
| e2-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -62.7% (±25.0%) |
| e2-mongo@1k | e2-base | steady | **p99 > 10× p50** | p50 126.0 ms, p99 2342.9 ms |
| e3-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +494.1% (±18.4%) |
| e3-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -34.0% (±18.4%) |
| e3-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +52.8% (±18.4%) |
| e3-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +35.5% (±18.4%) |
| e3-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -67.1% (±18.4%) |
| e3-mongo@100k | e1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +22.8% (±18.4%) |
| e3-mongo@100k | e1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +91.1% (±18.4%) |
| e3-mongo@100k | e2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +48.9% (±18.4%) |
| e3-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +78.0% (±18.4%) |
| e3-mongo@1k | head against base | cost | **more allocation** | head allocates ×1.08 the bytes per operation of base (median of the equal-rate pairs) |
| e3-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -28.8% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -29.3% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 38.7 ms, p99 610.3 ms |
| e3-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -45.6% (±25.0%) |
| e3-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -36.3% (±25.0%) |
| e3-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 33.3 ms, p99 458.8 ms |
| e3-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -85.1% (±25.0%) |
| e3-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +25.4% (±25.0%) |
| e3-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -40.6% (±25.0%) |
| e3-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -82.6% (±37.3%) |
| e3-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +78.5% (±37.3%) |
| e3-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -73.0% (±37.3%) |
| e3-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -41.4% (±37.3%) |
| e3-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -39.9% (±37.3%) |
| e3-pg@100k | r3-head | steady | **p99 > 10× p50** | p50 53.0 ms, p99 618.5 ms |
| e3-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 24.8 ms, p99 598.0 ms |
| e3-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -65.4% (±37.3%) |
| e3-pg@1k | head against base | cost | **more allocation** | head allocates ×1.06 the bytes per operation of base (median of the equal-rate pairs) |
| e3-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -78.0% (±25.0%) |
| e3-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 88.6 ms, p99 2703.4 ms |
| e3-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -64.7% (±25.0%) |
| e3-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 228.4 ms, p99 3309.6 ms |
| e3-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -91.9% (±25.0%) |
| e3-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 89.1 ms, p99 1482.8 ms |
| e3-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -75.4% (±25.0%) |
| e3-pg@1k | r2-base | steady | **p99 > 10× p50** | p50 45.8 ms, p99 819.2 ms |
| e3-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -76.1% (±25.0%) |
| e3-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 68.6 ms, p99 1032.2 ms |
| e3-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -78.0% (±25.0%) |
| e3-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 68.1 ms, p99 1351.7 ms |
| e3-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -82.4% (±25.0%) |
| e3-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 43.3 ms, p99 499.7 ms |
| e3-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -81.3% (±25.0%) |
| e3-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.0% (±25.0%) |
| e3-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 106.5 ms, p99 1974.3 ms |
| e3-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -79.3% (±25.0%) |
| mixed-mongo@100k | r2-head | steady | **p99 > 10× p50** | p50 23.4 ms, p99 234.5 ms |
| mixed-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 22.0 ms, p99 325.6 ms |
| mixed-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -33.1% (±25.0%) |
| mixed-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -52.5% (±25.0%) |
| mixed-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 20.6 ms, p99 292.9 ms |
| mixed-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -64.8% (±25.0%) |
| mixed-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -39.3% (±25.0%) |
| mixed-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -52.9% (±25.0%) |
| mixed-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -60.7% (±25.0%) |
| mixed-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -66.2% (±25.0%) |
| mixed-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -47.2% (±25.0%) |
| mixed-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 20.7 ms, p99 282.6 ms |
| mixed-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -46.9% (±25.0%) |
| mixed-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -27.3% (±12.2%) |
| mixed-pg@100k | r1-base | steady | **p99 > 10× p50** | p50 3.2 ms, p99 53.8 ms |
| mixed-pg@100k | r1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +41.8% (±12.2%) |
| mixed-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +25.2% (±12.2%) |
| mixed-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -59.8% (±12.2%) |
| mixed-pg@100k | r2-base | steady | **p99 > 10× p50** | p50 3.3 ms, p99 64.3 ms |
| mixed-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -39.4% (±12.2%) |
| mixed-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +53.0% (±12.2%) |
| mixed-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +31.3% (±12.2%) |
| mixed-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +105.4% (±12.2%) |
| mixed-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 2.9 ms, p99 72.2 ms |
| mixed-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +39.4% (±12.2%) |
| mixed-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -48.1% (±12.2%) |
| mixed-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -54.6% (±25.0%) |
| mixed-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 6.1 ms, p99 532.5 ms |
| mixed-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -79.1% (±25.0%) |
| mixed-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 8.1 ms, p99 749.6 ms |
| mixed-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -45.3% (±25.0%) |
| mixed-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 6.2 ms, p99 305.2 ms |
| mixed-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -60.7% (±25.0%) |
| mixed-pg@1k | r2-base | steady | **p99 > 10× p50** | p50 6.0 ms, p99 191.5 ms |
| mixed-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -52.2% (±25.0%) |
| mixed-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 6.5 ms, p99 227.3 ms |
| mixed-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.8% (±25.0%) |
| mixed-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 11.2 ms, p99 647.2 ms |
| mixed-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -48.7% (±25.0%) |
| mixed-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 8.0 ms, p99 393.2 ms |
| mixed-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.3% (±25.0%) |
| mixed-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 6.7 ms, p99 224.3 ms |
| mixed-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -38.9% (±25.0%) |
| mixed-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 4.9 ms, p99 257.0 ms |
| mixed-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -40.5% (±25.0%) |
| mixed-pg@1k | e2-base | steady | **p99 > 10× p50** | p50 4.8 ms, p99 215.0 ms |
| large-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +38.1% (±23.7%) |
| large-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +64.5% (±23.7%) |
| large-mongo@100k | r2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -52.1% (±23.7%) |
| large-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +71.5% (±23.7%) |
| large-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +123.1% (±23.7%) |
| large-mongo@100k | e2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +42.0% (±23.7%) |
| large-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -68.6% (±25.0%) |
| large-mongo@1k | r1-base | steady | **p99 > 10× p50** | p50 31.6 ms, p99 770.0 ms |
| large-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.9% (±25.0%) |
| large-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 20.9 ms, p99 1003.5 ms |
| large-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -27.2% (±25.0%) |
| large-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 24.8 ms, p99 766.0 ms |
| large-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -78.6% (±25.0%) |
| large-mongo@1k | r2-base | steady | **p99 > 10× p50** | p50 51.7 ms, p99 843.8 ms |
| large-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -63.5% (±25.0%) |
| large-mongo@1k | r3-base | steady | **p99 > 10× p50** | p50 48.9 ms, p99 921.6 ms |
| large-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -56.9% (±25.0%) |
| large-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 22.8 ms, p99 659.5 ms |
| large-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -62.9% (±25.0%) |
| large-mongo@1k | e1-base | steady | **p99 > 10× p50** | p50 15.3 ms, p99 626.7 ms |
| large-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -51.7% (±25.0%) |
| large-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 15.0 ms, p99 737.3 ms |
| large-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -31.9% (±25.0%) |
| large-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 19.6 ms, p99 831.5 ms |
| large-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -41.9% (±25.0%) |
| large-mongo@1k | e2-base | steady | **p99 > 10× p50** | p50 13.9 ms, p99 622.6 ms |
| large-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -90.3% (±47.9%) |
| large-pg@100k | r1-base | steady | **p99 > 10× p50** | p50 13.4 ms, p99 958.5 ms |
| large-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +79.4% (±47.9%) |
| large-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 -49.2% (±47.9%) |
| large-pg@100k | r3-base | steady | **p99 > 10× p50** | p50 64.5 ms, p99 745.5 ms |
| large-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -58.1% (±47.9%) |
| large-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 12.1 ms, p99 444.4 ms |
| large-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 -85.2% (±47.9%) |
| large-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the A/A noise: p50 +174.0% (±47.9%) |
| large-pg@100k | e2-head | steady | **p99 > 10× p50** | p50 78.3 ms, p99 946.2 ms |
| large-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the A/A noise: p50 +144.2% (±47.9%) |
| large-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -64.7% (±25.0%) |
| large-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 23.3 ms, p99 477.2 ms |
| large-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -94.1% (±25.0%) |
| large-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 113.2 ms, p99 4390.9 ms |
| large-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -74.5% (±25.0%) |
| large-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 116.2 ms, p99 3162.1 ms |
| large-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -80.1% (±25.0%) |
| large-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -79.3% (±25.0%) |
| large-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 142.3 ms, p99 3326.0 ms |
| large-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -94.1% (±25.0%) |
| large-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -76.1% (±25.0%) |
| large-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 126.0 ms, p99 2244.6 ms |
| large-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -87.5% (±25.0%) |
| large-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 115.7 ms, p99 3719.2 ms |
| large-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -83.9% (±25.0%) |
| large-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 52.5 ms, p99 3031.0 ms |
| large-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -87.1% (±25.0%) |
| large-pg@1k | e2-base | steady | **p99 > 10× p50** | p50 110.6 ms, p99 3457.0 ms |

## Significance

Beyond noise: 15 of 60 ratios; 10 of 20 suites have an A/A noise threshold (test/benchmarks/noise.json). A ratio counts when its 95% bootstrap interval (over the measured seconds) clears the suite's threshold; without one it is `uncalibrated`.

| Suite | b/a | Metric | Rounds | Ratio [95% CI] | Noise | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | throughput | calibrated | 0.902 [0.902–0.903] | ±2.0% | **lower** |
| e1-mongo-uniform@100k | head/base | p50 | equal | 1.629 [1.361–2.199] | ±19.9% | **higher** |
| e1-mongo-uniform@100k | head/base | p99 | equal | 1.430 [1.155–1.568] | ±12.1% | **higher** |
| e1-mongo-uniform@1k | head/base | throughput | calibrated | 1.001 [1.001–1.001] | - | uncalibrated |
| e1-mongo-uniform@1k | head/base | p50 | equal | 1.031 [0.569–1.650] | - | uncalibrated |
| e1-mongo-uniform@1k | head/base | p99 | equal | 0.800 [0.282–1.291] | - | uncalibrated |
| e1-mongo-zipf@100k | head/base | throughput | calibrated | 0.994 [0.994–0.994] | ±2.0% | within noise |
| e1-mongo-zipf@100k | head/base | p50 | equal | 1.299 [0.902–1.796] | ±27.4% | within noise |
| e1-mongo-zipf@100k | head/base | p99 | equal | 1.495 [0.906–1.578] | ±11.9% | within noise |
| e1-mongo-zipf@1k | head/base | throughput | calibrated | 1.024 [1.020–1.029] | - | uncalibrated |
| e1-mongo-zipf@1k | head/base | p50 | equal | 1.716 [1.206–3.338] | - | uncalibrated |
| e1-mongo-zipf@1k | head/base | p99 | equal | 1.427 [1.050–6.593] | - | uncalibrated |
| e1-pg-uniform@100k | head/base | throughput | calibrated | 0.821 [0.821–0.821] | ±2.0% | **lower** |
| e1-pg-uniform@100k | head/base | p50 | equal | 2.194 [0.829–5.705] | ±46.2% | within noise |
| e1-pg-uniform@100k | head/base | p99 | equal | 0.718 [1.283–3.053] | ±22.7% | **higher** |
| e1-pg-uniform@1k | head/base | throughput | calibrated | 1.030 [1.010–1.045] | - | uncalibrated |
| e1-pg-uniform@1k | head/base | p50 | equal | 1.296 [0.887–1.936] | - | uncalibrated |
| e1-pg-uniform@1k | head/base | p99 | equal | 1.498 [1.042–2.507] | - | uncalibrated |
| e2-mongo@100k | head/base | throughput | calibrated | 1.299 [1.299–1.299] | ±2.0% | **higher** |
| e2-mongo@100k | head/base | p50 | equal | 1.184 [0.837–1.460] | ±18.0% | within noise |
| e2-mongo@100k | head/base | p99 | equal | 1.048 [0.868–1.377] | ±11.6% | within noise |
| e2-mongo@1k | head/base | throughput | calibrated | 0.923 [0.922–0.924] | - | uncalibrated |
| e2-mongo@1k | head/base | p50 | equal | 1.110 [0.921–1.438] | - | uncalibrated |
| e2-mongo@1k | head/base | p99 | equal | 1.533 [0.899–1.373] | - | uncalibrated |
| e3-mongo@100k | head/base | throughput | calibrated | 0.942 [0.942–0.942] | ±2.0% | **lower** |
| e3-mongo@100k | head/base | p50 | equal | 1.717 [1.322–2.905] | ±18.4% | **higher** |
| e3-mongo@100k | head/base | p99 | equal | 1.847 [1.278–1.808] | ±9.1% | **higher** |
| e3-mongo@1k | head/base | throughput | calibrated | 0.907 [0.907–0.908] | - | uncalibrated |
| e3-mongo@1k | head/base | p50 | equal | 0.672 [0.689–1.189] | - | uncalibrated |
| e3-mongo@1k | head/base | p99 | equal | 0.674 [0.669–1.196] | - | uncalibrated |
| e3-pg@100k | head/base | throughput | calibrated | 0.911 [0.911–0.912] | ±2.0% | **lower** |
| e3-pg@100k | head/base | p50 | equal | 1.510 [0.906–3.107] | ±37.3% | within noise |
| e3-pg@100k | head/base | p99 | equal | 0.755 [0.900–2.400] | ±39.5% | within noise |
| e3-pg@1k | head/base | throughput | calibrated | 1.009 [1.009–1.010] | - | uncalibrated |
| e3-pg@1k | head/base | p50 | equal | 2.037 [0.740–4.769] | - | uncalibrated |
| e3-pg@1k | head/base | p99 | equal | 2.761 [1.015–3.235] | - | uncalibrated |
| mixed-mongo@100k | head/base | throughput | calibrated | 0.935 [0.935–0.935] | ±2.0% | **lower** |
| mixed-mongo@100k | head/base | p50 | equal | 1.114 [0.840–1.524] | ±62.3% | within noise |
| mixed-mongo@100k | head/base | p99 | equal | 1.806 [0.863–1.362] | ±16.8% | within noise |
| mixed-mongo@1k | head/base | throughput | calibrated | 1.015 [1.015–1.015] | - | uncalibrated |
| mixed-mongo@1k | head/base | p50 | equal | 1.840 [1.421–2.405] | - | uncalibrated |
| mixed-mongo@1k | head/base | p99 | equal | 2.742 [1.276–2.213] | - | uncalibrated |
| mixed-pg@100k | head/base | throughput | calibrated | 0.928 [0.928–0.929] | ±2.0% | **lower** |
| mixed-pg@100k | head/base | p50 | equal | 1.045 [0.561–1.850] | ±12.2% | within noise |
| mixed-pg@100k | head/base | p99 | equal | 1.943 [0.778–1.566] | ±56.3% | within noise |
| mixed-pg@1k | head/base | throughput | calibrated | 1.121 [1.121–1.121] | - | uncalibrated |
| mixed-pg@1k | head/base | p50 | equal | 0.900 [0.735–1.217] | - | uncalibrated |
| mixed-pg@1k | head/base | p99 | equal | 0.791 [0.620–1.457] | - | uncalibrated |
| large-mongo@100k | head/base | throughput | calibrated | 0.918 [0.918–0.918] | ±2.0% | **lower** |
| large-mongo@100k | head/base | p50 | equal | 0.932 [0.699–1.260] | ±23.7% | within noise |
| large-mongo@100k | head/base | p99 | equal | 0.849 [0.857–1.159] | ±17.1% | within noise |
| large-mongo@1k | head/base | throughput | calibrated | 0.757 [0.757–0.758] | - | uncalibrated |
| large-mongo@1k | head/base | p50 | equal | 1.186 [0.905–1.925] | - | uncalibrated |
| large-mongo@1k | head/base | p99 | equal | 1.256 [0.801–1.422] | - | uncalibrated |
| large-pg@100k | head/base | throughput | calibrated | 1.174 [1.174–1.174] | ±2.0% | **higher** |
| large-pg@100k | head/base | p50 | equal | 1.558 [1.688–6.370] | ±47.9% | **higher** |
| large-pg@100k | head/base | p99 | equal | 1.357 [1.106–2.023] | ±16.5% | within noise |
| large-pg@1k | head/base | throughput | calibrated | 1.093 [1.093–1.093] | - | uncalibrated |
| large-pg@1k | head/base | p50 | equal | 0.711 [0.378–1.157] | - | uncalibrated |
| large-pg@1k | head/base | p99 | equal | 1.184 [0.437–1.051] | - | uncalibrated |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| e1-mongo-uniform@1k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| e1-mongo-zipf@100k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| e1-mongo-zipf@1k | Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| e1-pg-uniform@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 2-3 · node 0-1 |
| e1-pg-uniform@1k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 2-3 · node 0-1 |
| e2-mongo@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| e2-mongo@1k | Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| e3-mongo@100k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| e3-mongo@1k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| e3-pg@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 2-3 · node 0-1 |
| e3-pg@1k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 2-3 · node 0-1 |
| mixed-mongo@100k | Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| mixed-mongo@1k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| mixed-pg@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 2-3 · node 0-1 |
| mixed-pg@1k | Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 2-3 · node 0-1 |
| large-mongo@100k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| large-mongo@1k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0 · store 1 · Kafka 2 |
| large-pg@100k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 2-3 · node 0-1 |
| large-pg@1k | AMD EPYC 7763 64-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 2-3 · node 0-1 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | base | 3468.8/s | 2048 | 2775.0/s | 80% |
| e1-mongo-uniform@100k | head | 3128.9/s | 2048 | 2503.1/s | 80% |
| e1-mongo-uniform@1k | base | 2006.1/s | 2048 | 677.1/s | 34% (**not sustained**) |
| e1-mongo-uniform@1k | head | 2008.5/s | 2048 | 677.9/s | 34% (**not sustained**) |
| e1-mongo-zipf@100k | base | 1770.9/s | 1024 | 1416.7/s | 80% |
| e1-mongo-zipf@100k | head | 1760.3/s | 1024 | 1408.2/s | 80% |
| e1-mongo-zipf@1k | base | 1686.4/s | 1024 | 569.2/s | 34% (**not sustained**) |
| e1-mongo-zipf@1k | head | 1721.0/s | 1024 | 580.8/s | 34% (**not sustained**) |
| e1-pg-uniform@100k | base | 1990.0/s | 512 | 1592.0/s | 80% |
| e1-pg-uniform@100k | head | 1633.6/s | 64 | 1306.9/s | 80% |
| e1-pg-uniform@1k | base | ≥ 2059.9/s | 4096 | 695.2/s | 34% (**not sustained**) |
| e1-pg-uniform@1k | head | ≥ 2165.8/s | 4096 | 730.9/s | 34% (**not sustained**) |
| e2-mongo@100k | base | 959.4/s | 2048 | 575.6/s | 60% |
| e2-mongo@100k | head | 934.6/s | 1024 | 747.7/s | 80% |
| e2-mongo@1k | base | ≥ 1223.6/s | 4096 | 413.0/s | 34% (**not sustained**) |
| e2-mongo@1k | head | ≥ 1129.0/s | 4096 | 381.0/s | 34% (**not sustained**) |
| e3-mongo@100k | base | 681.4/s | 256 | 545.1/s | 80% |
| e3-mongo@100k | head | 641.6/s | 256 | 513.3/s | 80% |
| e3-mongo@1k | base | 860.1/s | 1024 | 290.3/s | 34% |
| e3-mongo@1k | head | 780.4/s | 1024 | 263.4/s | 34% |
| e3-pg@100k | base | 590.5/s | 32 | 472.4/s | 80% |
| e3-pg@100k | head | 717.5/s | 512 | 430.5/s | 60% |
| e3-pg@1k | base | 578.4/s | 1024 | 195.2/s | 34% |
| e3-pg@1k | head | 583.8/s | 2048 | 197.0/s | 34% |
| mixed-mongo@100k | base | 2044.5/s | 256 | 1226.7/s | 60% |
| mixed-mongo@100k | head | 1912.1/s | 256 | 1147.3/s | 60% |
| mixed-mongo@1k | base | 1515.1/s | 512 | 511.4/s | 34% (**not sustained**) |
| mixed-mongo@1k | head | 1538.4/s | 256 | 519.2/s | 34% (**not sustained**) |
| mixed-pg@100k | base | 2187.8/s | 64 | 984.5/s | 45% |
| mixed-pg@100k | head | 2031.4/s | 64 | 914.1/s | 45% |
| mixed-pg@1k | base | 1379.8/s | 128 | 465.7/s | 34% (**not sustained**) |
| mixed-pg@1k | head | 1546.8/s | 256 | 522.0/s | 34% (**not sustained**) |
| large-mongo@100k | base | 1098.8/s | 2048 | 659.3/s | 60% |
| large-mongo@100k | head | 1008.8/s | 256 | 605.3/s | 60% |
| large-mongo@1k | base | 2257.3/s | 1024 | 1015.8/s | 45% |
| large-mongo@1k | head | 1709.9/s | 32 | 769.4/s | 45% |
| large-pg@100k | base | 813.8/s | 8 | 651.0/s | 80% |
| large-pg@100k | head | 955.5/s | 64 | 764.4/s | 80% |
| large-pg@1k | base | 891.9/s | 2048 | 301.0/s | 34% |
| large-pg@1k | head | 974.9/s | 2048 | 329.0/s | 34% (**not sustained**) |

## Paired ratios

`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.

| Suite | b/a | Rounds | Saturation | Throughput | p50 | p95 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | head/base | calibrated (unequal load) | 0.902 | 0.902 (0.902–0.903, n=3) | 0.684 (0.495–0.778, n=3) | 0.716 (0.631–1.110, n=3) | 0.764 (0.672–1.307, n=3) | - | 0.747 (0.653–1.176, n=3) | 1.099 (1.080–1.100, n=3) | 1.038 (1.034–1.054, n=3) |
| e1-mongo-uniform@100k | head/base | equal rate 2503.1/s | 0.902 | 0.993 (0.988–0.999, n=2) | 2.011 (1.076–2.945, n=2) | 1.620 (1.125–2.114, n=2) | 1.490 (1.128–1.853, n=2) | - | 1.425 (1.117–1.733, n=2) | 1.020 (0.982–1.058, n=2) | 1.001 (0.979–1.023, n=2) |
| e1-mongo-uniform@1k | head/base | calibrated (unequal load) | 1.001 | 1.001 (1.001–1.001, n=3) | 0.747 (0.540–1.635, n=3) | 1.216 (1.100–1.504, n=3) | 1.185 (1.099–1.468, n=3) | - | 1.299 (1.168–1.547, n=3) | 1.001 (0.990–1.010, n=3) | 0.995 (0.991–1.064, n=3) |
| e1-mongo-uniform@1k | head/base | equal rate 677.1/s | 1.001 | 1.006 (1.000–1.013, n=2) | 1.413 (0.593–2.234, n=2) | 1.199 (0.968–1.431, n=2) | 0.976 (0.561–1.391, n=2) | - | 0.944 (0.444–1.444, n=2) | 0.989 (0.984–0.994, n=2) | 0.997 (0.963–1.031, n=2) |
| e1-mongo-zipf@100k | head/base | calibrated (unequal load) | 0.994 | 0.994 (0.994–0.994, n=3) | 1.133 (0.824–1.434, n=3) | 1.297 (0.970–1.617, n=3) | 1.228 (0.909–1.477, n=3) | - | 1.055 (0.805–1.149, n=3) | 1.018 (0.982–1.024, n=3) | 1.012 (0.992–1.045, n=3) |
| e1-mongo-zipf@100k | head/base | equal rate 1408.2/s | 0.994 | 1.000 (1.000–1.000, n=2) | 1.298 (1.222–1.374, n=2) | 1.470 (1.063–1.878, n=2) | 1.524 (1.089–1.959, n=2) | - | 1.732 (1.046–2.419, n=2) | 1.013 (1.009–1.017, n=2) | 1.007 (1.005–1.009, n=2) |
| e1-mongo-zipf@1k | head/base | calibrated (unequal load) | 1.021 | 1.020 (1.020–1.030, n=3) | 1.405 (0.957–2.376, n=3) | 3.474 (0.898–5.407, n=3) | 1.396 (1.030–1.440, n=3) | - | 1.308 (0.825–1.367, n=3) | 0.978 (0.954–0.985, n=3) | 0.994 (0.966–0.996, n=3) |
| e1-mongo-zipf@1k | head/base | equal rate 569.2/s | 1.021 | 1.000 (1.000–1.000, n=2) | 1.744 (1.625–1.864, n=2) | 4.572 (2.569–6.576, n=2) | 1.434 (1.378–1.490, n=2) | - | 1.303 (1.204–1.403, n=2) | 0.998 (0.992–1.003, n=2) | 0.992 (0.989–0.996, n=2) |
| e1-pg-uniform@100k | head/base | calibrated (unequal load) | 0.821 | 0.821 (0.821–0.821, n=3) | 0.218 (0.123–0.226, n=3) | 0.350 (0.273–0.423, n=3) | 0.398 (0.332–0.463, n=3) | - | 0.429 (0.397–0.468, n=3) | 1.215 (1.213–1.217, n=3) | 1.077 (1.075–1.078, n=3) |
| e1-pg-uniform@100k | head/base | equal rate 1306.9/s | 0.821 | 1.000 (1.000–1.000, n=2) | 2.184 (1.985–2.382, n=2) | 0.952 (0.741–1.163, n=2) | 0.916 (0.449–1.382, n=2) | - | 1.125 (0.433–1.818, n=2) | 1.000 (1.000–1.001, n=2) | 1.035 (1.033–1.037, n=2) |
| e1-pg-uniform@1k | head/base | calibrated (unequal load) | ~1.051 | 1.029 (1.017–1.044, n=3) | 1.589 (1.309–1.675, n=3) | 1.161 (1.031–1.178, n=3) | 1.118 (1.010–1.896, n=3) | - | 1.068 (0.965–2.226, n=3) | 0.969 (0.954–0.983, n=3) | 1.021 (0.991–1.028, n=3) |
| e1-pg-uniform@1k | head/base | equal rate 695.2/s | ~1.051 | 0.981 (0.973–0.990, n=2) | 1.340 (1.047–1.634, n=2) | 1.151 (0.865–1.437, n=2) | 1.470 (1.273–1.667, n=2) | - | 1.621 (1.176–2.065, n=2) | 1.013 (1.005–1.022, n=2) | 1.024 (1.021–1.027, n=2) |
| e2-mongo@100k | head/base | calibrated (unequal load) | 0.974 | 1.299 (1.299–1.299, n=3) | 2.861 (1.929–5.969, n=3) | 2.682 (2.521–3.285, n=3) | 2.722 (2.573–2.900, n=3) | - | 2.754 (2.521–3.000, n=3) | 0.815 (0.793–0.834, n=3) | 0.931 (0.921–0.949, n=3) |
| e2-mongo@100k | head/base | equal rate 575.6/s | 0.974 | 1.000 (1.000–1.000, n=2) | 1.188 (0.965–1.410, n=2) | 1.135 (0.698–1.571, n=2) | 1.115 (0.681–1.549, n=2) | - | 1.195 (0.760–1.630, n=2) | 0.986 (0.981–0.991, n=2) | 0.996 (0.978–1.015, n=2) |
| e2-mongo@1k | head/base | calibrated (unequal load) | ~0.923 | 0.923 (0.923–0.924, n=3) | 1.036 (0.091–1.533, n=3) | 1.348 (0.393–2.509, n=3) | 1.304 (0.433–2.204, n=3) | - | 1.356 (0.500–2.103, n=3) | 1.086 (1.063–1.095, n=3) | 1.038 (1.025–1.065, n=3) |
| e2-mongo@1k | head/base | equal rate 381.0/s | ~0.923 | 1.000 (1.000–1.000, n=2) | 1.117 (1.041–1.194, n=2) | 1.781 (1.327–2.236, n=2) | 1.621 (1.266–1.977, n=2) | - | 1.704 (1.470–1.939, n=2) | 0.990 (0.985–0.995, n=2) | 1.019 (1.018–1.020, n=2) |
| e3-mongo@100k | head/base | calibrated (unequal load) | 0.942 | 0.942 (0.942–0.942, n=3) | 1.021 (0.337–1.198, n=3) | 1.492 (0.242–1.883, n=3) | 1.598 (0.252–1.698, n=3) | - | 1.409 (0.299–1.569, n=3) | 1.054 (1.047–1.160, n=3) | 1.035 (1.033–1.089, n=3) |
| e3-mongo@100k | head/base | equal rate 513.3/s | 0.942 | 1.000 (1.000–1.000, n=2) | 1.707 (1.559–1.854, n=2) | 2.253 (2.134–2.372, n=2) | 1.851 (1.769–1.933, n=2) | - | 1.528 (1.465–1.591, n=2) | 0.984 (0.972–0.997, n=2) | 0.987 (0.982–0.993, n=2) |
| e3-mongo@1k | head/base | calibrated (unequal load) | 0.907 | 0.907 (0.907–0.907, n=3) | 0.915 (0.800–0.987, n=3) | 1.213 (0.550–1.533, n=3) | 2.251 (0.577–2.649, n=3) | - | 2.279 (0.503–2.497, n=3) | 1.082 (1.047–1.112, n=3) | 1.071 (1.063–1.102, n=3) |
| e3-mongo@1k | head/base | equal rate 263.4/s | 0.907 | 1.000 (1.000–1.000, n=2) | 0.814 (0.421–1.208, n=2) | 1.069 (0.354–1.785, n=2) | 0.877 (0.433–1.321, n=2) | - | 0.976 (0.540–1.412, n=2) | 1.066 (0.980–1.152, n=2) | 1.076 (1.020–1.133, n=2) |
| e3-pg@100k | head/base | calibrated (unequal load) | 1.215 | 0.911 (0.911–0.911, n=3) | 0.460 (0.386–1.183, n=3) | 0.852 (0.508–1.897, n=3) | 0.813 (0.683–1.961, n=3) | - | 0.760 (0.752–1.710, n=3) | 1.098 (1.095–1.100, n=3) | 1.088 (1.081–1.094, n=3) |
| e3-pg@100k | head/base | equal rate 430.5/s | 1.215 | 1.000 (1.000–1.000, n=2) | 1.518 (1.407–1.629, n=2) | 1.001 (0.664–1.338, n=2) | 0.957 (0.538–1.376, n=2) | - | 1.134 (0.769–1.500, n=2) | 1.007 (1.006–1.007, n=2) | 1.050 (1.037–1.063, n=2) |
| e3-pg@1k | head/base | calibrated (unequal load) | 1.009 | 1.009 (1.009–1.009, n=3) | 1.944 (0.993–2.578, n=3) | 1.276 (1.198–1.859, n=3) | 1.310 (1.224–1.810, n=3) | - | 1.141 (1.139–1.701, n=3) | 0.992 (0.990–0.997, n=3) | 1.067 (1.036–1.076, n=3) |
| e3-pg@1k | head/base | equal rate 195.2/s | 1.009 | 1.000 (1.000–1.000, n=2) | 2.056 (1.479–2.633, n=2) | 3.300 (1.074–5.526, n=2) | 3.002 (0.984–5.021, n=2) | - | 2.125 (0.842–3.408, n=2) | 1.002 (1.001–1.004, n=2) | 1.059 (1.050–1.068, n=2) |
| mixed-mongo@100k | head/base | calibrated (unequal load) | 0.935 | 0.935 (0.935–0.935, n=3) | 0.887 (0.833–1.011, n=3) | 0.996 (0.911–1.283, n=3) | 1.000 (0.937–1.355, n=3) | - | 0.974 (0.938–1.199, n=3) | 1.059 (1.048–1.074, n=3) | 1.023 (1.023–1.036, n=3) |
| mixed-mongo@100k | head/base | equal rate 1147.3/s | 0.935 | 1.000 (1.000–1.000, n=2) | 1.113 (1.064–1.162, n=2) | 1.549 (1.136–1.963, n=2) | 1.812 (1.268–2.356, n=2) | - | 1.818 (1.296–2.340, n=2) | 0.994 (0.989–1.000, n=2) | 1.006 (1.002–1.009, n=2) |
| mixed-mongo@1k | head/base | calibrated (unequal load) | 1.015 | 1.015 (1.015–1.015, n=3) | 1.436 (1.158–2.284, n=3) | 1.769 (1.328–2.538, n=3) | 1.884 (1.420–3.306, n=3) | - | 2.500 (1.733–3.196, n=3) | 0.971 (0.970–0.978, n=3) | 0.989 (0.951–1.016, n=3) |
| mixed-mongo@1k | head/base | equal rate 511.4/s | 1.015 | 1.000 (1.000–1.000, n=2) | 1.837 (1.623–2.051, n=2) | 1.990 (1.694–2.286, n=2) | 2.654 (1.922–3.387, n=2) | - | 2.810 (1.392–4.229, n=2) | 0.987 (0.982–0.991, n=2) | 0.993 (0.992–0.993, n=2) |
| mixed-pg@100k | head/base | calibrated (unequal load) | 0.929 | 0.928 (0.928–0.928, n=3) | 0.747 (0.699–0.754, n=3) | 0.637 (0.617–0.857, n=3) | 0.594 (0.548–0.950, n=3) | - | 1.120 (0.593–1.193, n=3) | 1.049 (1.033–1.055, n=3) | 1.031 (1.030–1.033, n=3) |
| mixed-pg@100k | head/base | equal rate 914.1/s | 0.929 | 1.000 (1.000–1.000, n=2) | 1.045 (1.040–1.051, n=2) | 1.267 (1.210–1.324, n=2) | 1.926 (1.683–2.169, n=2) | - | 1.354 (1.177–1.531, n=2) | 0.998 (0.989–1.008, n=2) | 1.026 (1.024–1.027, n=2) |
| mixed-pg@1k | head/base | calibrated (unequal load) | 1.121 | 1.121 (1.121–1.121, n=3) | 1.325 (1.043–1.716, n=3) | 2.480 (2.310–4.299, n=3) | 1.594 (1.408–2.847, n=3) | - | 1.150 (0.707–1.469, n=3) | 0.899 (0.897–0.902, n=3) | 1.024 (1.021–1.030, n=3) |
| mixed-pg@1k | head/base | equal rate 465.7/s | 1.121 | 1.000 (1.000–1.000, n=2) | 0.924 (0.829–1.020, n=2) | 0.827 (0.763–0.891, n=2) | 0.883 (0.570–1.195, n=2) | - | 1.055 (0.980–1.130, n=2) | 1.002 (0.996–1.008, n=2) | 1.027 (1.027–1.028, n=2) |
| large-mongo@100k | head/base | calibrated (unequal load) | 0.918 | 0.918 (0.918–0.918, n=3) | 0.995 (0.845–1.031, n=3) | 0.921 (0.920–1.189, n=3) | 1.033 (0.918–1.037, n=3) | - | 1.018 (0.842–1.155, n=3) | 1.071 (1.043–1.124, n=3) | 1.019 (1.019–1.032, n=3) |
| large-mongo@100k | head/base | equal rate 605.3/s | 0.918 | 1.000 (1.000–1.000, n=2) | 0.930 (0.922–0.939, n=2) | 0.886 (0.795–0.977, n=2) | 0.881 (0.713–1.049, n=2) | - | 1.087 (0.779–1.395, n=2) | 1.005 (0.984–1.025, n=2) | 1.014 (1.012–1.015, n=2) |
| large-mongo@1k | head/base | calibrated (unequal load) | 0.758 | 0.757 (0.757–0.757, n=3) | 0.480 (0.466–0.660, n=3) | 0.815 (0.594–1.336, n=3) | 0.908 (0.716–1.303, n=3) | - | 0.909 (0.776–1.257, n=3) | 1.321 (1.305–1.331, n=3) | 1.061 (1.031–1.074, n=3) |
| large-mongo@1k | head/base | equal rate 769.4/s | 0.758 | 1.000 (1.000–1.000, n=2) | 1.197 (0.983–1.410, n=2) | 1.333 (1.128–1.538, n=2) | 1.256 (1.176–1.336, n=2) | - | 1.244 (1.228–1.260, n=2) | 0.975 (0.973–0.977, n=2) | 1.005 (0.984–1.027, n=2) |
| large-pg@100k | head/base | calibrated (unequal load) | 1.174 | 1.174 (1.174–1.174, n=3) | 2.492 (0.509–9.876, n=3) | 1.218 (0.601–1.343, n=3) | 1.000 (0.625–1.335, n=3) | - | 0.994 (0.663–1.303, n=3) | 0.932 (0.881–0.951, n=3) | 1.019 (0.999–1.021, n=3) |
| large-pg@100k | head/base | equal rate 651.0/s | 1.174 | 1.000 (1.000–1.000, n=2) | 4.054 (0.827–7.281, n=2) | 1.467 (1.053–1.881, n=2) | 1.474 (1.105–1.843, n=2) | - | 1.608 (1.129–2.088, n=2) | 0.979 (0.932–1.026, n=2) | 1.000 (0.991–1.009, n=2) |
| large-pg@1k | head/base | calibrated (unequal load) | 1.093 | 1.093 (1.093–1.093, n=3) | 5.017 (4.857–5.525, n=3) | 12.150 (2.378–16.121, n=3) | 9.202 (2.079–14.775, n=3) | - | 6.818 (1.985–11.492, n=3) | 0.919 (0.914–0.922, n=3) | 1.043 (1.014–1.064, n=3) |
| large-pg@1k | head/base | equal rate 301.0/s | 1.093 | 1.000 (1.000–1.000, n=2) | 0.697 (0.475–0.919, n=2) | 1.320 (0.804–1.836, n=2) | 1.267 (0.877–1.657, n=2) | - | 1.276 (0.821–1.731, n=2) | 1.002 (1.002–1.003, n=2) | 1.024 (1.011–1.037, n=2) |

---

### e1-mongo-uniform@100k

E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (33–40 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 3468.8/s | 2048 | 2775.0/s | 80% |
| head | 3128.9/s | 2048 | 2503.1/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 2775.0/s | 2775.0/s | 55500 | 0 | 148.5 ms | 438.3 ms | 553.0 ms | n/a | 610.3 ms | 0.76 cores | kafka 36% · mongo 97% | 0.53 of 1 | mongo 0.97 | 0.75 | 92.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | - |
| probe1-head | head | 2503.1/s | 2503.1/s | 50062 | 0 | 98.8 ms | 297.0 ms | 397.3 ms | n/a | 495.6 ms | 0.75 cores | kafka 37% · mongo 94% | 0.50 of 1 | mongo 0.94 | 0.83 | 95.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 2775.0/s | 2775.0/s | 111000 | 0 | 193.5 ms | 532.5 ms | 725.0 ms | n/a | 991.2 ms | 0.71 cores | kafka 37% · mongo 97% | 0.49 of 1 | mongo 0.97 | 0.74 | 92.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 19 s | - |
| r1-head | head | 2503.1/s | 2503.1/s | 100124 | 0 | 95.7 ms | 335.9 ms | 487.4 ms | n/a | 647.2 ms | 0.70 cores | kafka 36% · mongo 96% | 0.45 of 1 | mongo 0.96 | 0.81 | 97.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | - |
| r2-head | head | 2503.1/s | 2503.1/s | 100124 | 0 | 146.4 ms | 428.0 ms | 622.6 ms | n/a | 737.3 ms | 0.70 cores | kafka 37% · mongo 95% | 0.45 of 1 | mongo 0.95 | 0.81 | 95.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 21 s | - |
| r2-base | base | 2775.0/s | 2772.2/s | 110887 | 113 | 214.0 ms | 598.0 ms | 815.1 ms | n/a | 987.1 ms | 0.73 cores | kafka 37% · mongo 97% | 0.51 of 1 | mongo 0.97 | 0.75 | 91.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 24 s | generator-overload 113 |
| r3-base | base | 2775.0/s | 2775.0/s | 111000 | 0 | 189.4 ms | 501.8 ms | 626.7 ms | n/a | 835.6 ms | 0.73 cores | kafka 36% · mongo 97% | 0.50 of 1 | mongo 0.97 | 0.74 | 92.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 20 s | - |
| r3-head | head | 2503.1/s | 2503.1/s | 100124 | 0 | 147.5 ms | 557.1 ms | 819.2 ms | n/a | 983.0 ms | 0.71 cores | kafka 37% · mongo 96% | 0.46 of 1 | mongo 0.96 | 0.82 | 95.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 24 s | - |
| e1-base | base | 2503.1/s | 2503.1/s | 100124 | 0 | 268.3 ms | 589.8 ms | 737.3 ms | n/a | 913.4 ms | 0.72 cores | kafka 42% · mongo 98% | 0.49 of 1 | mongo 0.98 | 0.85 | 91.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 19 s | - |
| e1-head | head | 2503.1/s | 2499.6/s | 99984 | 140 | 288.8 ms | 663.6 ms | 831.5 ms | n/a | 1019.9 ms | 0.74 cores | kafka 39% · mongo 95% | 0.48 of 1 | mongo 0.95 | 0.83 | 94.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 29 s | generator-overload 140 |
| e2-head | head | 2503.1/s | 2472.1/s | 98883 | 1241 | 331.8 ms | 757.8 ms | 978.9 ms | n/a | 1384.4 ms | 0.73 cores | kafka 40% · mongo 98% | 0.49 of 1 | mongo 0.98 | 0.85 | 91.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 25 s | generator-overload 1241 |
| e2-base | base | 2503.1/s | 2503.1/s | 100124 | 0 | 112.6 ms | 358.4 ms | 528.4 ms | n/a | 798.7 ms | 0.69 cores | kafka 37% · mongo 96% | 0.46 of 1 | mongo 0.96 | 0.81 | 93.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.902 | 0.495 | 0.631 | 0.672 | - | 0.653 | 1.099 | 1.054 |
| 2 | 0.903 | 0.684 | 0.716 | 0.764 | - | 0.747 | 1.080 | 1.038 |
| 3 | 0.902 | 0.778 | 1.110 | 1.307 | - | 1.176 | 1.100 | 1.034 |

Pairs at one rate for both arms, 2503.1/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.999 | 1.076 | 1.125 | 1.128 | - | 1.117 | 0.982 | 1.023 |
| 2 | 0.988 | 2.945 | 2.114 | 1.853 | - | 1.733 | 1.058 | 0.979 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-uniform@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +25.1% (±25.0%) |
| e1-mongo-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +103.5% (±25.0%) |
| e1-mongo-uniform@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +40.2% (±25.0%) |
| e1-mongo-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +62.1% (±25.0%) |
| e1-mongo-uniform@100k | e2-head | steady | **error spike** | 1241 of 100124 failed: generator-overload 1241; generator-overload: 2000 in flight at most (per-target ceiling) |


### e1-mongo-uniform@1k

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


### e1-mongo-zipf@100k

E1 write on MongoDB, Zipf keys (hot accounts, lane and cache contention): head against baseline.

E1, zipf keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (60–63 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1770.9/s | 1024 | 1416.7/s | 80% |
| head | 1760.3/s | 1024 | 1408.2/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1416.7/s | 1416.7/s | 28334 | 0 | 76.3 ms | 331.8 ms | 577.5 ms | n/a | 778.2 ms | 0.89 cores | kafka 50% · mongo 96% | 0.63 of 1 | mongo 0.96 | 1.66 | 97.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| probe1-head | head | 1408.2/s | 1408.2/s | 28164 | 0 | 81.4 ms | 387.1 ms | 675.8 ms | n/a | 1466.4 ms | 0.88 cores | kafka 52% · mongo 93% | 0.63 of 1 | mongo 0.93 | 1.66 | 96.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1416.7/s | 1416.7/s | 56668 | 0 | 48.1 ms | 227.3 ms | 368.6 ms | n/a | 593.9 ms | 0.75 cores | kafka 51% · mongo 98% | 0.45 of 1 | mongo 0.98 | 1.59 | 98.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r1-head | head | 1408.2/s | 1408.2/s | 56328 | 0 | 54.5 ms | 294.9 ms | 452.6 ms | n/a | 626.7 ms | 0.78 cores | kafka 52% · mongo 97% | 0.45 of 1 | mongo 0.97 | 1.61 | 99.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| r2-head | head | 1408.2/s | 1408.2/s | 56328 | 0 | 75.3 ms | 354.3 ms | 532.5 ms | n/a | 725.0 ms | 0.82 cores | kafka 52% · mongo 97% | 0.44 of 1 | mongo 0.97 | 1.64 | 97.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r2-base | base | 1416.7/s | 1416.7/s | 56668 | 0 | 52.5 ms | 219.1 ms | 360.4 ms | n/a | 630.8 ms | 0.78 cores | kafka 52% · mongo 97% | 0.47 of 1 | mongo 0.97 | 1.60 | 98.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r3-base | base | 1416.7/s | 1416.7/s | 56668 | 0 | 58.1 ms | 274.4 ms | 495.6 ms | n/a | 880.6 ms | 0.77 cores | kafka 51% · mongo 98% | 0.46 of 1 | mongo 0.98 | 1.59 | 96.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| r3-head | head | 1408.2/s | 1408.2/s | 56328 | 0 | 47.9 ms | 266.2 ms | 450.6 ms | n/a | 708.6 ms | 0.77 cores | kafka 46% · mongo 98% | 0.43 of 1 | mongo 0.98 | 1.57 | 100.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e1-base | base | 1408.2/s | 1408.2/s | 56328 | 0 | 54.8 ms | 250.9 ms | 403.5 ms | n/a | 518.1 ms | 0.77 cores | kafka 52% · mongo 98% | 0.44 of 1 | mongo 0.98 | 1.61 | 97.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| e1-head | head | 1408.2/s | 1408.2/s | 56328 | 0 | 75.3 ms | 471.0 ms | 790.5 ms | n/a | 1253.4 ms | 0.81 cores | kafka 53% · mongo 97% | 0.44 of 1 | mongo 0.97 | 1.64 | 98.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| e2-head | head | 1408.2/s | 1408.2/s | 56328 | 0 | 64.8 ms | 311.3 ms | 501.8 ms | n/a | 745.5 ms | 0.80 cores | kafka 53% · mongo 97% | 0.44 of 1 | mongo 0.97 | 1.63 | 98.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e2-base | base | 1408.2/s | 1408.2/s | 56328 | 0 | 53.0 ms | 292.9 ms | 460.8 ms | n/a | 712.7 ms | 0.79 cores | kafka 51% · mongo 98% | 0.44 of 1 | mongo 0.98 | 1.62 | 97.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.994 | 1.133 | 1.297 | 1.228 | - | 1.055 | 1.018 | 1.012 |
| 2 | 0.994 | 1.434 | 1.617 | 1.477 | - | 1.149 | 1.024 | 0.992 |
| 3 | 0.994 | 0.824 | 0.970 | 0.909 | - | 0.805 | 0.982 | 1.045 |

Pairs at one rate for both arms, 1408.2/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.374 | 1.878 | 1.959 | - | 2.419 | 1.017 | 1.005 |
| 2 | 1.000 | 1.222 | 1.063 | 1.089 | - | 1.046 | 1.009 | 1.009 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-zipf@100k | r1-base | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r1-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r2-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@100k | r2-base | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r3-base | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +27.5% (±25.0%) |
| e1-mongo-zipf@100k | r3-head | steady | **tail read the store** | overflow=4 |
| e1-mongo-zipf@100k | e1-base | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +51.9% (±25.0%) |
| e1-mongo-zipf@100k | e1-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.0% (±25.0%) |
| e1-mongo-zipf@100k | e1-head | steady | **p99 > 10× p50** | p50 75.3 ms, p99 790.5 ms |
| e1-mongo-zipf@100k | e2-head | steady | **tail read the store** | overflow=3 |
| e1-mongo-zipf@100k | e2-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@100k | probe1-base | overload (probe) | **tail read the store** | overflow=1 |
| e1-mongo-zipf@100k | probe1-head | overload (probe) | **tail read the store** | overflow=1 |


### e1-mongo-zipf@1k

E1 write on MongoDB, Zipf keys (hot accounts, lane and cache contention): head against baseline.

E1, zipf keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1686.4/s | 1024 | 569.2/s | 34% (**not sustained**) |
| head | 1721.0/s | 1024 | 580.8/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1349.1/s | 585.1/s | 11703 | 15279 | 1269.8 ms | 7897.1 ms | 9895.9 ms | n/a | 10223.6 ms | 0.99 cores | kafka 63% · mongo 51% | 0.45 of 1 | server 0.99 | 3.65 | 45.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | generator-overload 13839 · http-504-wow.wait.timeout 918 · http-429-wow.command.busy 522 |
| probe2-base | base | 1011.8/s | 624.3/s | 12486 | 7750 | 1212.4 ms | 6979.6 ms | 9699.3 ms | n/a | 10223.6 ms | 1.00 cores | kafka 65% · mongo 52% | 0.43 of 1 | server 1.00 | 3.47 | 64.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | generator-overload 6216 · http-504-wow.wait.timeout 924 · http-429-wow.command.busy 610 |
| probe3-base | base | 758.9/s | 540.4/s | 10807 | 4371 | 1130.5 ms | 9502.7 ms | 10223.6 ms | n/a | 10551.3 ms | 0.99 cores | kafka 65% · mongo 52% | 0.40 of 1 | server 0.99 | 4.01 | 77.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | generator-overload 3178 · http-504-wow.wait.timeout 1026 · http-429-wow.command.busy 167 |
| probe4-base | base | 569.2/s | 533.5/s | 10671 | 712 | 794.6 ms | 7307.3 ms | 10092.5 ms | n/a | 10503.2 ms | 0.99 cores | kafka 65% · mongo 54% | 0.39 of 1 | server 0.99 | 4.09 | 104.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | http-504-wow.wait.timeout 598 · http-429-wow.command.busy 114 |
| probe1-head | head | 1376.8/s | 570.1/s | 11402 | 16134 | 1343.5 ms | 8585.2 ms | 10158.1 ms | n/a | 10616.8 ms | 1.00 cores | kafka 64% · mongo 53% | 0.43 of 1 | server 1.00 | 3.79 | 43.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 12 s | generator-overload 14929 · http-504-wow.wait.timeout 847 · http-429-wow.command.busy 358 |
| probe2-head | head | 1032.6/s | 573.4/s | 11467 | 9184 | 1294.3 ms | 8060.9 ms | 9109.5 ms | n/a | 10130.5 ms | 0.99 cores | kafka 65% · mongo 52% | 0.47 of 1 | server 0.99 | 3.78 | 58.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 13 s | generator-overload 7863 · http-504-wow.wait.timeout 1000 · http-429-wow.command.busy 321 |
| probe3-head | head | 774.5/s | 557.0/s | 11141 | 4349 | 1286.1 ms | 7831.6 ms | 9240.6 ms | n/a | 10027.0 ms | 0.99 cores | kafka 63% · mongo 53% | 0.43 of 1 | server 0.99 | 3.84 | 77.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | generator-overload 3017 · http-504-wow.wait.timeout 976 · http-429-wow.command.busy 356 |
| probe4-head | head | 580.8/s | 525.0/s | 10500 | 1116 | 999.4 ms | 9502.7 ms | 9961.5 ms | n/a | 10289.2 ms | 1.00 cores | kafka 65% · mongo 55% | 0.37 of 1 | server 1.00 | 4.18 | 98.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | generator-overload 727 · http-504-wow.wait.timeout 354 · http-429-wow.command.busy 35 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 569.2/s | 569.2/s | 22767 | 0 | 86.0 ms | 622.6 ms | 4390.9 ms | n/a | 5435.2 ms | 1.00 cores | kafka 57% · mongo 71% | 0.23 of 1 | server 1.00 | 4.01 | 122.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r1-head | head | 580.8/s | 580.8/s | 23232 | 0 | 120.8 ms | 2162.7 ms | 6127.6 ms | n/a | 7110.7 ms | 1.00 cores | kafka 53% · mongo 70% | 0.22 of 1 | server 1.00 | 3.82 | 121.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r2-head | head | 580.8/s | 580.7/s | 23229 | 3 | 136.2 ms | 2883.6 ms | 6750.2 ms | n/a | 7897.1 ms | 1.00 cores | kafka 56% · mongo 68% | 0.23 of 1 | server 1.00 | 3.85 | 121.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | http-429-wow.command.busy 3 |
| r2-base | base | 569.2/s | 563.6/s | 22546 | 221 | 142.3 ms | 3211.3 ms | 6553.6 ms | n/a | 9568.3 ms | 1.00 cores | kafka 53% · mongo 67% | 0.22 of 1 | server 1.00 | 3.91 | 121.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | generator-overload 221 |
| r3-base | base | 569.2/s | 569.2/s | 22767 | 0 | 58.6 ms | 593.9 ms | 4620.3 ms | n/a | 5537.8 ms | 1.00 cores | kafka 53% · mongo 73% | 0.22 of 1 | server 1.00 | 3.96 | 124.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r3-head | head | 580.8/s | 580.8/s | 23232 | 0 | 139.3 ms | 3211.3 ms | 6651.9 ms | n/a | 7569.4 ms | 1.00 cores | kafka 56% · mongo 69% | 0.23 of 1 | server 1.00 | 3.87 | 120.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-base | base | 569.2/s | 569.2/s | 22767 | 0 | 122.9 ms | 1900.5 ms | 5898.2 ms | n/a | 7241.7 ms | 1.00 cores | kafka 51% · mongo 70% | 0.21 of 1 | server 1.00 | 3.87 | 119.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e1-head | head | 569.2/s | 569.2/s | 22767 | 0 | 199.7 ms | 4882.4 ms | 8126.5 ms | n/a | 8716.3 ms | 1.00 cores | kafka 56% · mongo 66% | 0.23 of 1 | server 1.00 | 3.89 | 118.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-head | head | 569.2/s | 569.2/s | 22767 | 0 | 140.3 ms | 3555.3 ms | 6979.6 ms | n/a | 8323.1 ms | 1.00 cores | kafka 54% · mongo 68% | 0.23 of 1 | server 1.00 | 3.90 | 121.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-base | base | 569.2/s | 569.2/s | 22767 | 0 | 75.3 ms | 540.7 ms | 4685.8 ms | n/a | 5931.0 ms | 1.00 cores | kafka 53% · mongo 71% | 0.22 of 1 | server 1.00 | 3.93 | 121.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.020 | 1.405 | 3.474 | 1.396 | - | 1.308 | 0.954 | 0.996 |
| 2 | 1.030 | 0.957 | 0.898 | 1.030 | - | 0.825 | 0.985 | 0.994 |
| 3 | 1.020 | 2.376 | 5.407 | 1.440 | - | 1.367 | 0.978 | 0.966 |

Pairs at one rate for both arms, 569.2/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.625 | 2.569 | 1.378 | - | 1.204 | 1.003 | 0.989 |
| 2 | 1.000 | 1.864 | 6.576 | 1.490 | - | 1.403 | 0.992 | 0.996 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-mongo-zipf@1k | r1-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -84.2% (±25.0%) |
| e1-mongo-zipf@1k | r1-base | steady | **p99 > 10× p50** | p50 86.0 ms, p99 4390.9 ms |
| e1-mongo-zipf@1k | r1-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -81.5% (±25.0%) |
| e1-mongo-zipf@1k | r1-head | steady | **p99 > 10× p50** | p50 120.8 ms, p99 6127.6 ms |
| e1-mongo-zipf@1k | r2-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -74.5% (±25.0%) |
| e1-mongo-zipf@1k | r2-head | steady | **p99 > 10× p50** | p50 136.2 ms, p99 6750.2 ms |
| e1-mongo-zipf@1k | r2-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -82.8% (±25.0%) |
| e1-mongo-zipf@1k | r2-base | steady | **p99 > 10× p50** | p50 142.3 ms, p99 6553.6 ms |
| e1-mongo-zipf@1k | r3-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -85.3% (±25.0%) |
| e1-mongo-zipf@1k | r3-base | steady | **p99 > 10× p50** | p50 58.6 ms, p99 4620.3 ms |
| e1-mongo-zipf@1k | r3-head | steady | **tail read the store** | overflow=1 |
| e1-mongo-zipf@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -79.4% (±25.0%) |
| e1-mongo-zipf@1k | r3-head | steady | **p99 > 10× p50** | p50 139.3 ms, p99 6651.9 ms |
| e1-mongo-zipf@1k | e1-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -68.4% (±25.0%) |
| e1-mongo-zipf@1k | e1-base | steady | **p99 > 10× p50** | p50 122.9 ms, p99 5898.2 ms |
| e1-mongo-zipf@1k | e1-head | steady | **tail read the store** | overflow=1 |
| e1-mongo-zipf@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.1% (±25.0%) |
| e1-mongo-zipf@1k | e1-head | steady | **p99 > 10× p50** | p50 199.7 ms, p99 8126.5 ms |
| e1-mongo-zipf@1k | e2-head | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.6% (±25.0%) |
| e1-mongo-zipf@1k | e2-head | steady | **p99 > 10× p50** | p50 140.3 ms, p99 6979.6 ms |
| e1-mongo-zipf@1k | e2-base | steady | **tail read the store** | overflow=2 |
| e1-mongo-zipf@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -79.0% (±25.0%) |
| e1-mongo-zipf@1k | e2-base | steady | **p99 > 10× p50** | p50 75.3 ms, p99 4685.8 ms |


### e1-pg-uniform@100k

E1 write on PostgreSQL: head against baseline.

E1, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (65–75 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1990.0/s | 512 | 1592.0/s | 80% |
| head | 1633.6/s | 64 | 1306.9/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1592.0/s | 1592.0/s | 31840 | 0 | 335.9 ms | 958.5 ms | 1179.6 ms | n/a | 1327.1 ms | 0.90 cores | kafka 45% · postgres 64% | 0.59 of 2 | server 0.90 | 1.25 | 86.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | - |
| probe1-head | head | 1306.9/s | 1295.2/s | 25903 | 235 | 234.5 ms | 1015.8 ms | 1450.0 ms | n/a | 1876.0 ms | 0.90 cores | kafka 50% · postgres 63% | 0.51 of 2 | server 0.90 | 1.57 | 95.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 11 s | generator-overload 235 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1592.0/s | 1592.0/s | 63680 | 0 | 170.0 ms | 614.4 ms | 774.1 ms | n/a | 962.6 ms | 0.86 cores | kafka 51% · postgres 65% | 0.55 of 2 | server 0.86 | 1.27 | 87.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 5 s | - |
| r1-head | head | 1306.9/s | 1306.9/s | 52275 | 0 | 37.1 ms | 260.1 ms | 358.4 ms | n/a | 450.6 ms | 0.88 cores | kafka 48% · postgres 66% | 0.49 of 2 | server 0.88 | 1.54 | 94.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| r2-head | head | 1306.9/s | 1306.9/s | 52275 | 0 | 59.1 ms | 244.7 ms | 389.1 ms | n/a | 573.4 ms | 0.86 cores | kafka 50% · postgres 66% | 0.50 of 2 | server 0.86 | 1.55 | 94.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| r2-base | base | 1592.0/s | 1592.0/s | 63680 | 0 | 261.1 ms | 897.0 ms | 1171.5 ms | n/a | 1335.3 ms | 0.87 cores | kafka 50% · postgres 66% | 0.55 of 2 | server 0.87 | 1.27 | 87.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 17 s | - |
| r3-base | base | 1592.0/s | 1592.0/s | 63680 | 0 | 206.8 ms | 602.1 ms | 766.0 ms | n/a | 1036.3 ms | 0.86 cores | kafka 50% · postgres 67% | 0.54 of 2 | server 0.86 | 1.27 | 87.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 15 s | - |
| r3-head | head | 1306.9/s | 1306.9/s | 52275 | 0 | 25.3 ms | 210.9 ms | 305.2 ms | n/a | 411.6 ms | 0.86 cores | kafka 49% · postgres 67% | 0.49 of 2 | server 0.86 | 1.55 | 94.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| e1-base | base | 1306.9/s | 1306.9/s | 52275 | 0 | 17.5 ms | 301.1 ms | 688.1 ms | n/a | 974.8 ms | 0.84 cores | kafka 49% · postgres 69% | 0.51 of 2 | server 0.84 | 1.54 | 91.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| e1-head | head | 1306.9/s | 1306.9/s | 52275 | 0 | 34.8 ms | 223.2 ms | 309.2 ms | n/a | 421.9 ms | 0.88 cores | kafka 49% · postgres 64% | 0.48 of 2 | server 0.88 | 1.54 | 95.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 2 s | - |
| e2-head | head | 1306.9/s | 1306.9/s | 52275 | 0 | 46.3 ms | 241.7 ms | 385.0 ms | n/a | 614.4 ms | 0.90 cores | kafka 50% · postgres 62% | 0.48 of 2 | server 0.90 | 1.55 | 94.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| e2-base | base | 1306.9/s | 1306.9/s | 52275 | 0 | 19.5 ms | 207.9 ms | 278.5 ms | n/a | 337.9 ms | 0.88 cores | kafka 49% · postgres 65% | 0.49 of 2 | server 0.88 | 1.54 | 91.6 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.821 | 0.218 | 0.423 | 0.463 | - | 0.468 | 1.217 | 1.077 |
| 2 | 0.821 | 0.226 | 0.273 | 0.332 | - | 0.429 | 1.215 | 1.075 |
| 3 | 0.821 | 0.123 | 0.350 | 0.398 | - | 0.397 | 1.213 | 1.078 |

Pairs at one rate for both arms, 1306.9/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.985 | 0.741 | 0.449 | - | 0.433 | 1.000 | 1.037 |
| 2 | 1.000 | 2.382 | 1.163 | 1.382 | - | 1.818 | 1.001 | 1.033 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-pg-uniform@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -26.4% (±25.0%) |
| e1-pg-uniform@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -29.2% (±25.0%) |
| e1-pg-uniform@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +47.6% (±25.0%) |
| e1-pg-uniform@100k | r3-head | steady | **p99 > 10× p50** | p50 25.3 ms, p99 305.2 ms |
| e1-pg-uniform@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -53.5% (±25.0%) |
| e1-pg-uniform@100k | e1-base | steady | **p99 > 10× p50** | p50 17.5 ms, p99 688.1 ms |
| e1-pg-uniform@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +104.4% (±25.0%) |
| e1-pg-uniform@100k | e2-base | steady | **p99 > 10× p50** | p50 19.5 ms, p99 278.5 ms |


### e1-pg-uniform@1k

E1 write on PostgreSQL: head against baseline.

E1, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | ≥ 2059.9/s | 4096 | 695.2/s | 34% (**not sustained**) |
| head | ≥ 2165.8/s | 4096 | 730.9/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1647.9/s | 595.9/s | 11917 | 21041 | 3113.0 ms | 5242.9 ms | 6062.1 ms | n/a | 7110.7 ms | 1.13 cores | kafka 68% · postgres 17% | 0.45 of 2 | server 1.13 | 3.34 | 35.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | generator-overload 21041 |
| probe2-base | base | 1235.9/s | 585.5/s | 11710 | 13008 | 3113.0 ms | 5341.2 ms | 6258.7 ms | n/a | 7241.7 ms | 1.14 cores | kafka 66% · postgres 18% | 0.45 of 2 | server 1.14 | 3.38 | 45.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | generator-overload 13008 |
| probe3-base | base | 926.9/s | 608.2/s | 12164 | 6374 | 2965.5 ms | 4816.9 ms | 5668.9 ms | n/a | 6389.8 ms | 1.16 cores | kafka 66% · postgres 18% | 0.41 of 2 | server 1.16 | 3.28 | 63.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | generator-overload 6374 |
| probe4-base | base | 695.2/s | 543.0/s | 10859 | 3045 | 3113.0 ms | 5406.7 ms | 6422.5 ms | n/a | 7209.0 ms | 1.16 cores | kafka 65% · postgres 17% | 0.42 of 2 | server 1.16 | 3.64 | 78.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 8 s | generator-overload 3045 |
| probe1-head | head | 1732.6/s | 529.0/s | 10581 | 24071 | 3620.9 ms | 5996.5 ms | 7045.1 ms | n/a | 7798.8 ms | 1.13 cores | kafka 68% · postgres 16% | 0.50 of 2 | server 1.13 | 3.73 | 30.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | generator-overload 24071 |
| probe2-head | head | 1299.5/s | 566.9/s | 11337 | 14653 | 3227.6 ms | 5439.5 ms | 6553.6 ms | n/a | 7602.2 ms | 1.19 cores | kafka 63% · postgres 17% | 0.46 of 2 | server 1.19 | 3.52 | 43.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | generator-overload 14653 |
| probe3-head | head | 974.6/s | 587.4/s | 11748 | 7744 | 3096.6 ms | 5242.9 ms | 6422.5 ms | n/a | 7929.9 ms | 1.17 cores | kafka 64% · postgres 18% | 0.47 of 2 | server 1.17 | 3.39 | 60.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | generator-overload 7744 |
| probe4-head | head | 730.9/s | 558.0/s | 11160 | 3458 | 3096.6 ms | 5341.2 ms | 6225.9 ms | n/a | 7110.7 ms | 1.17 cores | kafka 65% · postgres 18% | 0.43 of 2 | server 1.17 | 3.59 | 79.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | generator-overload 3458 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 695.2/s | 693.9/s | 27754 | 53 | 340.0 ms | 2113.5 ms | 3063.8 ms | n/a | 4112.4 ms | 1.10 cores | kafka 56% · postgres 37% | 0.28 of 2 | server 1.10 | 2.92 | 100.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 15 s | generator-overload 53 |
| r1-head | head | 730.9/s | 724.1/s | 28966 | 270 | 569.3 ms | 2490.4 ms | 3424.3 ms | n/a | 4390.9 ms | 1.10 cores | kafka 57% · postgres 35% | 0.28 of 2 | server 1.10 | 2.79 | 102.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | generator-overload 270 |
| r2-head | head | 730.9/s | 701.9/s | 28074 | 1162 | 585.7 ms | 2605.1 ms | 5996.5 ms | n/a | 9699.3 ms | 1.13 cores | kafka 55% · postgres 34% | 0.28 of 2 | server 1.13 | 2.88 | 100.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | generator-overload 851 · http-504-wow.wait.timeout 311 |
| r2-base | base | 695.2/s | 689.9/s | 27595 | 212 | 368.6 ms | 2244.6 ms | 3162.1 ms | n/a | 4358.1 ms | 1.11 cores | kafka 55% · postgres 37% | 0.28 of 2 | server 1.11 | 2.93 | 100.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | generator-overload 212 |
| r3-base | base | 695.2/s | 693.4/s | 27735 | 72 | 497.7 ms | 2605.1 ms | 3424.3 ms | n/a | 4685.8 ms | 1.10 cores | kafka 58% · postgres 34% | 0.27 of 2 | server 1.10 | 2.92 | 98.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | generator-overload 72 |
| r3-head | head | 730.9/s | 713.8/s | 28553 | 683 | 651.3 ms | 2687.0 ms | 3457.0 ms | n/a | 4522.0 ms | 1.14 cores | kafka 54% · postgres 33% | 0.28 of 2 | server 1.14 | 2.83 | 101.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | generator-overload 683 |
| e1-base | base | 695.2/s | 688.9/s | 27557 | 250 | 528.4 ms | 2670.6 ms | 3735.6 ms | n/a | 5013.5 ms | 1.08 cores | kafka 60% · postgres 34% | 0.27 of 2 | server 1.08 | 2.93 | 97.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | generator-overload 250 |
| e1-head | head | 695.2/s | 670.1/s | 26804 | 1003 | 553.0 ms | 2310.1 ms | 6225.9 ms | n/a | 10354.7 ms | 1.10 cores | kafka 58% · postgres 33% | 0.30 of 2 | server 1.10 | 3.00 | 99.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | http-504-wow.wait.timeout 732 · generator-overload 271 |
| e2-head | head | 695.2/s | 677.4/s | 27094 | 713 | 639.0 ms | 2719.7 ms | 3588.1 ms | n/a | 4587.5 ms | 1.13 cores | kafka 55% · postgres 33% | 0.26 of 2 | server 1.13 | 2.97 | 101.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | generator-overload 713 |
| e2-base | base | 695.2/s | 684.1/s | 27364 | 443 | 391.2 ms | 1892.4 ms | 2818.0 ms | n/a | 3899.4 ms | 1.11 cores | kafka 55% · postgres 36% | 0.28 of 2 | server 1.11 | 2.96 | 98.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 11 s | generator-overload 443 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.044 | 1.675 | 1.178 | 1.118 | - | 1.068 | 0.954 | 1.021 |
| 2 | 1.017 | 1.589 | 1.161 | 1.896 | - | 2.226 | 0.983 | 0.991 |
| 3 | 1.029 | 1.309 | 1.031 | 1.010 | - | 0.965 | 0.969 | 1.028 |

Pairs at one rate for both arms, 695.2/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.973 | 1.047 | 0.865 | 1.667 | - | 2.065 | 1.022 | 1.021 |
| 2 | 0.990 | 1.634 | 1.437 | 1.273 | - | 1.176 | 1.005 | 1.027 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e1-pg-uniform@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -73.4% (±25.0%) |
| e1-pg-uniform@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -65.7% (±25.0%) |
| e1-pg-uniform@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: throughput +8.6% (±5.0%), p50 -72.3% (±25.0%) |
| e1-pg-uniform@1k | r2-head | steady | **error spike** | 1162 of 29236 failed: generator-overload 851 · http-504-wow.wait.timeout 311; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-pg-uniform@1k | r2-head | steady | **throughput dip** | 1 s below half the rate (lowest 224/s) |
| e1-pg-uniform@1k | r2-head | steady | **p99 > 10× p50** | p50 585.7 ms, p99 5996.5 ms |
| e1-pg-uniform@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -87.1% (±25.0%) |
| e1-pg-uniform@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -88.4% (±25.0%) |
| e1-pg-uniform@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -80.7% (±25.0%) |
| e1-pg-uniform@1k | r3-head | steady | **error spike** | 683 of 29236 failed: generator-overload 683; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-pg-uniform@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -81.6% (±25.0%) |
| e1-pg-uniform@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: throughput +7.8% (±5.0%), p50 -54.8% (±25.0%) |
| e1-pg-uniform@1k | e1-head | steady | **error spike** | 1003 of 27807 failed: http-504-wow.wait.timeout 732 · generator-overload 271; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-pg-uniform@1k | e1-head | steady | **p99 > 10× p50** | p50 553.0 ms, p99 6225.9 ms |
| e1-pg-uniform@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: throughput +5.4% (±5.0%), p50 -81.7% (±25.0%) |
| e1-pg-uniform@1k | e2-head | steady | **error spike** | 713 of 27807 failed: generator-overload 713; generator-overload: 2000 in flight at most (per-target ceiling) |
| e1-pg-uniform@1k | e2-head | steady | **throughput dip** | 1 s below half the rate (lowest 206/s) |
| e1-pg-uniform@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -76.2% (±25.0%) |
| e1-pg-uniform@1k | e2-base | steady | **error spike** | 443 of 27807 failed: generator-overload 443; generator-overload: 2000 in flight at most (per-target ceiling) |


### e2-mongo@100k

E2 read-your-write on MongoDB: head against baseline.

E2, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (66–74 s), then waited until the nodes were quiet again (3–5 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 959.4/s | 2048 | 575.6/s | 60% |
| head | 934.6/s | 1024 | 747.7/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 767.5/s | 743.0/s | 14860 | 490 | 2048.0 ms | 3244.0 ms | 3670.0 ms | n/a | 4227.1 ms | 0.95 cores | kafka 44% · mongo 93% | 0.59 of 1 | server 0.95 | 3.12 | 168.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 20 s | generator-overload 490 |
| probe2-base | base | 575.6/s | 575.6/s | 11512 | 0 | 120.3 ms | 419.8 ms | 610.3 ms | n/a | 790.5 ms | 0.83 cores | kafka 50% · mongo 94% | 0.57 of 1 | mongo 0.94 | 3.95 | 188.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| probe1-head | head | 747.7/s | 745.5/s | 14911 | 43 | 1622.0 ms | 2703.4 ms | 3407.9 ms | n/a | 4014.1 ms | 0.88 cores | kafka 47% · mongo 96% | 0.67 of 1 | mongo 0.96 | 3.10 | 182.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | generator-overload 43 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 575.6/s | 575.6/s | 23024 | 0 | 72.2 ms | 169.0 ms | 216.1 ms | n/a | 290.8 ms | 0.74 cores | kafka 50% · mongo 96% | 0.36 of 1 | mongo 0.96 | 3.81 | 190.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r1-head | head | 747.7/s | 747.7/s | 29908 | 0 | 139.3 ms | 426.0 ms | 626.7 ms | n/a | 872.4 ms | 0.79 cores | kafka 50% · mongo 97% | 0.44 of 1 | mongo 0.97 | 3.02 | 180.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| r2-head | head | 747.7/s | 747.7/s | 29908 | 0 | 391.2 ms | 696.3 ms | 942.1 ms | n/a | 1376.3 ms | 0.87 cores | kafka 51% · mongo 97% | 0.44 of 1 | mongo 0.97 | 3.15 | 175.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | - |
| r2-base | base | 575.6/s | 575.6/s | 23024 | 0 | 65.5 ms | 212.0 ms | 346.1 ms | n/a | 499.7 ms | 0.71 cores | kafka 49% · mongo 97% | 0.37 of 1 | mongo 0.97 | 3.77 | 190.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r3-base | base | 575.6/s | 575.6/s | 23024 | 0 | 70.1 ms | 180.2 ms | 246.8 ms | n/a | 337.9 ms | 0.74 cores | kafka 46% · mongo 97% | 0.37 of 1 | mongo 0.97 | 3.76 | 189.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| r3-head | head | 747.7/s | 747.7/s | 29908 | 0 | 200.7 ms | 483.3 ms | 634.9 ms | n/a | 852.0 ms | 0.81 cores | kafka 50% · mongo 98% | 0.43 of 1 | mongo 0.98 | 3.07 | 176.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e1-base | base | 575.6/s | 575.6/s | 23024 | 0 | 71.2 ms | 200.7 ms | 272.4 ms | n/a | 387.1 ms | 0.74 cores | kafka 51% · mongo 97% | 0.38 of 1 | mongo 0.97 | 3.85 | 190.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e1-head | head | 575.6/s | 575.6/s | 23024 | 0 | 100.4 ms | 315.4 ms | 421.9 ms | n/a | 630.8 ms | 0.72 cores | kafka 50% · mongo 97% | 0.38 of 1 | mongo 0.97 | 3.81 | 186.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e2-head | head | 575.6/s | 575.6/s | 23024 | 0 | 70.7 ms | 184.3 ms | 254.0 ms | n/a | 362.5 ms | 0.75 cores | kafka 46% · mongo 95% | 0.37 of 1 | mongo 0.95 | 3.76 | 192.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e2-base | base | 575.6/s | 575.6/s | 23024 | 0 | 73.2 ms | 264.2 ms | 372.7 ms | n/a | 477.2 ms | 0.73 cores | kafka 51% · mongo 96% | 0.36 of 1 | mongo 0.96 | 3.84 | 189.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.299 | 1.929 | 2.521 | 2.900 | - | 3.000 | 0.793 | 0.949 |
| 2 | 1.299 | 5.969 | 3.285 | 2.722 | - | 2.754 | 0.834 | 0.921 |
| 3 | 1.299 | 2.861 | 2.682 | 2.573 | - | 2.521 | 0.815 | 0.931 |

Pairs at one rate for both arms, 575.6/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.410 | 1.571 | 1.549 | - | 1.630 | 0.991 | 0.978 |
| 2 | 1.000 | 0.965 | 0.698 | 0.681 | - | 0.760 | 0.981 | 1.015 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e2-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -26.3% (±25.0%) |


### e2-mongo@1k

E2 read-your-write on MongoDB: head against baseline.

E2, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | ≥ 1223.6/s | 4096 | 413.0/s | 34% (**not sustained**) |
| head | ≥ 1129.0/s | 4096 | 381.0/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 978.9/s | 372.9/s | 7458 | 12120 | 4718.6 ms | 7372.8 ms | 8159.2 ms | n/a | 10616.8 ms | 0.99 cores | kafka 53% · mongo 57% | 0.39 of 1 | server 0.99 | 5.61 | 75.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | generator-overload 12120 |
| probe2-base | base | 734.2/s | 350.9/s | 7018 | 7666 | 5013.5 ms | 8060.9 ms | 9306.1 ms | n/a | 10420.2 ms | 0.99 cores | kafka 52% · mongo 55% | 0.39 of 1 | server 0.99 | 5.89 | 95.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | generator-overload 7666 |
| probe3-base | base | 550.6/s | 388.8/s | 7776 | 3236 | 4653.1 ms | 7012.4 ms | 8028.2 ms | n/a | 9175.0 ms | 0.99 cores | kafka 55% · mongo 58% | 0.40 of 1 | server 0.99 | 5.47 | 133.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 15 s | generator-overload 3236 |
| probe4-base | base | 413.0/s | 355.1/s | 7102 | 1158 | 4816.9 ms | 7405.6 ms | 8388.6 ms | n/a | 9961.5 ms | 0.99 cores | kafka 55% · mongo 58% | 0.42 of 1 | server 0.99 | 5.98 | 171.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | generator-overload 1158 |
| probe1-head | head | 903.2/s | 374.4/s | 7489 | 10575 | 4718.6 ms | 7045.1 ms | 7995.4 ms | n/a | 9437.2 ms | 0.99 cores | kafka 53% · mongo 58% | 0.41 of 1 | server 0.99 | 5.63 | 83.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 15 s | generator-overload 10575 |
| probe2-head | head | 677.4/s | 420.8/s | 8416 | 5132 | 4325.4 ms | 6291.5 ms | 7209.0 ms | n/a | 7897.1 ms | 1.00 cores | kafka 49% · mongo 59% | 0.37 of 1 | server 1.00 | 4.95 | 121.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 16 s | generator-overload 5132 |
| probe3-head | head | 508.1/s | 340.3/s | 6806 | 3356 | 5046.3 ms | 7700.5 ms | 9109.5 ms | n/a | 10289.2 ms | 0.99 cores | kafka 53% · mongo 58% | 0.46 of 1 | server 0.99 | 6.18 | 135.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | generator-overload 3356 |
| probe4-head | head | 381.0/s | 350.8/s | 7016 | 604 | 4685.8 ms | 7143.4 ms | 8585.2 ms | n/a | 10289.2 ms | 0.99 cores | kafka 56% · mongo 59% | 0.42 of 1 | server 0.99 | 6.11 | 186.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 18 s | generator-overload 604 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 413.0/s | 412.4/s | 16497 | 23 | 1204.2 ms | 4587.5 ms | 5603.3 ms | n/a | 7045.1 ms | 1.00 cores | kafka 52% · mongo 70% | 0.23 of 1 | server 1.00 | 5.37 | 205.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 17 s | generator-overload 23 |
| r1-head | head | 381.0/s | 381.0/s | 15240 | 0 | 109.1 ms | 1802.2 ms | 2424.8 ms | n/a | 3522.6 ms | 1.00 cores | kafka 50% · mongo 74% | 0.21 of 1 | server 1.00 | 5.88 | 218.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r2-head | head | 381.0/s | 381.0/s | 15240 | 0 | 141.3 ms | 1695.7 ms | 2474.0 ms | n/a | 3358.7 ms | 1.00 cores | kafka 48% · mongo 73% | 0.21 of 1 | server 1.00 | 5.79 | 215.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| r2-base | base | 413.0/s | 413.0/s | 16520 | 0 | 92.2 ms | 675.8 ms | 1122.3 ms | n/a | 1597.4 ms | 0.99 cores | kafka 49% · mongo 77% | 0.23 of 1 | server 0.99 | 5.45 | 210.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r3-base | base | 413.0/s | 413.0/s | 16520 | 0 | 142.3 ms | 1835.0 ms | 2424.8 ms | n/a | 3080.2 ms | 1.00 cores | kafka 48% · mongo 74% | 0.22 of 1 | server 1.00 | 5.37 | 209.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r3-head | head | 381.0/s | 381.0/s | 15240 | 0 | 147.5 ms | 2474.0 ms | 3162.1 ms | n/a | 4177.9 ms | 1.00 cores | kafka 49% · mongo 73% | 0.22 of 1 | server 1.00 | 5.82 | 217.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| e1-base | base | 381.0/s | 381.0/s | 15240 | 0 | 102.9 ms | 938.0 ms | 1409.0 ms | n/a | 1867.8 ms | 0.99 cores | kafka 49% · mongo 75% | 0.22 of 1 | server 0.99 | 5.86 | 213.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e1-head | head | 381.0/s | 381.0/s | 15240 | 0 | 122.9 ms | 2097.2 ms | 2785.3 ms | n/a | 3620.9 ms | 1.00 cores | kafka 49% · mongo 74% | 0.22 of 1 | server 1.00 | 5.84 | 217.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| e2-head | head | 381.0/s | 381.0/s | 15240 | 0 | 131.1 ms | 2228.2 ms | 2965.5 ms | n/a | 4358.1 ms | 1.00 cores | kafka 48% · mongo 73% | 0.22 of 1 | server 1.00 | 5.79 | 214.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | - |
| e2-base | base | 381.0/s | 381.0/s | 15240 | 0 | 126.0 ms | 1679.4 ms | 2342.9 ms | n/a | 2965.5 ms | 1.00 cores | kafka 49% · mongo 75% | 0.21 of 1 | server 1.00 | 5.88 | 211.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.924 | 0.091 | 0.393 | 0.433 | - | 0.500 | 1.095 | 1.065 |
| 2 | 0.923 | 1.533 | 2.509 | 2.204 | - | 2.103 | 1.063 | 1.025 |
| 3 | 0.923 | 1.036 | 1.348 | 1.304 | - | 1.356 | 1.086 | 1.038 |

Pairs at one rate for both arms, 381.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.194 | 2.236 | 1.977 | - | 1.939 | 0.995 | 1.020 |
| 2 | 1.000 | 1.041 | 1.327 | 1.266 | - | 1.470 | 0.985 | 1.018 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e2-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -94.4% (±25.0%) |
| e2-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -64.9% (±25.0%) |
| e2-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 109.1 ms, p99 2424.8 ms |
| e2-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -63.0% (±25.0%) |
| e2-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 141.3 ms, p99 2474.0 ms |
| e2-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -59.6% (±25.0%) |
| e2-mongo@1k | r2-base | steady | **p99 > 10× p50** | p50 92.2 ms, p99 1122.3 ms |
| e2-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -68.9% (±25.0%) |
| e2-mongo@1k | r3-base | steady | **p99 > 10× p50** | p50 142.3 ms, p99 2424.8 ms |
| e2-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -86.9% (±25.0%) |
| e2-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 147.5 ms, p99 3162.1 ms |
| e2-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -48.6% (±25.0%) |
| e2-mongo@1k | e1-base | steady | **p99 > 10× p50** | p50 102.9 ms, p99 1409.0 ms |
| e2-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -72.1% (±25.0%) |
| e2-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 122.9 ms, p99 2785.3 ms |
| e2-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -84.3% (±25.0%) |
| e2-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 131.1 ms, p99 2965.5 ms |
| e2-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -62.7% (±25.0%) |
| e2-mongo@1k | e2-base | steady | **p99 > 10× p50** | p50 126.0 ms, p99 2342.9 ms |


### e3-mongo@100k

E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (47–52 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 681.4/s | 256 | 545.1/s | 80% |
| head | 641.6/s | 256 | 513.3/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 545.1/s | 545.1/s | 10902 | 0 | 96.8 ms | 274.4 ms | 376.8 ms | n/a | 528.4 ms | 0.56 cores | kafka 38% · mongo 97% | 0.29 of 1 | mongo 0.97 | 3.50 | 266.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| probe1-head | head | 513.3/s | 513.3/s | 10265 | 0 | 115.2 ms | 393.2 ms | 495.6 ms | n/a | 589.8 ms | 0.57 cores | kafka 43% · mongo 92% | 0.32 of 1 | mongo 0.92 | 3.73 | 272.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 545.1/s | 545.1/s | 21803 | 0 | 307.2 ms | 1359.9 ms | 1728.5 ms | n/a | 1957.9 ms | 0.50 cores | kafka 40% · mongo 88% | 0.17 of 1 | mongo 0.88 | 3.25 | 254.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 23 s | - |
| r1-head | head | 513.3/s | 513.3/s | 20532 | 0 | 103.4 ms | 329.7 ms | 436.2 ms | n/a | 585.7 ms | 0.55 cores | kafka 41% · mongo 98% | 0.14 of 1 | mongo 0.98 | 3.77 | 276.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| r2-head | head | 513.3/s | 513.3/s | 20532 | 0 | 98.3 ms | 391.2 ms | 602.1 ms | n/a | 819.2 ms | 0.53 cores | kafka 40% · mongo 96% | 0.14 of 1 | mongo 0.96 | 3.69 | 273.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| r2-base | base | 545.1/s | 545.1/s | 21803 | 0 | 96.3 ms | 262.1 ms | 376.8 ms | n/a | 522.2 ms | 0.53 cores | kafka 41% · mongo 97% | 0.15 of 1 | mongo 0.97 | 3.50 | 264.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r3-base | base | 545.1/s | 545.1/s | 21803 | 0 | 98.3 ms | 385.0 ms | 528.4 ms | n/a | 700.4 ms | 0.53 cores | kafka 41% · mongo 96% | 0.15 of 1 | mongo 0.96 | 3.48 | 266.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| r3-head | head | 513.3/s | 513.3/s | 20532 | 0 | 117.8 ms | 725.0 ms | 897.0 ms | n/a | 987.1 ms | 0.54 cores | kafka 40% · mongo 93% | 0.17 of 1 | mongo 0.93 | 3.64 | 274.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | - |
| e1-base | base | 513.3/s | 513.3/s | 20532 | 0 | 73.2 ms | 274.4 ms | 430.1 ms | n/a | 610.3 ms | 0.52 cores | kafka 38% · mongo 95% | 0.14 of 1 | mongo 0.95 | 3.61 | 274.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e1-head | head | 513.3/s | 513.3/s | 20532 | 0 | 114.2 ms | 585.7 ms | 831.5 ms | n/a | 970.8 ms | 0.52 cores | kafka 40% · mongo 93% | 0.15 of 1 | mongo 0.93 | 3.60 | 270.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 10 s | - |
| e2-head | head | 513.3/s | 513.3/s | 20532 | 0 | 155.6 ms | 704.5 ms | 847.9 ms | n/a | 954.4 ms | 0.51 cores | kafka 40% · mongo 91% | 0.14 of 1 | mongo 0.91 | 3.54 | 266.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 14 s | - |
| e2-base | base | 513.3/s | 513.3/s | 20532 | 0 | 84.0 ms | 297.0 ms | 479.2 ms | n/a | 651.3 ms | 0.53 cores | kafka 39% · mongo 95% | 0.16 of 1 | mongo 0.95 | 3.65 | 268.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.942 | 0.337 | 0.242 | 0.252 | - | 0.299 | 1.160 | 1.089 |
| 2 | 0.942 | 1.021 | 1.492 | 1.598 | - | 1.569 | 1.054 | 1.035 |
| 3 | 0.942 | 1.198 | 1.883 | 1.698 | - | 1.409 | 1.047 | 1.033 |

Pairs at one rate for both arms, 513.3/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.559 | 2.134 | 1.933 | - | 1.591 | 0.997 | 0.982 |
| 2 | 1.000 | 1.854 | 2.372 | 1.769 | - | 1.465 | 0.972 | 0.993 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +494.1% (±25.0%) |
| e3-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -34.0% (±25.0%) |
| e3-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +52.8% (±25.0%) |
| e3-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +35.5% (±25.0%) |
| e3-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -67.1% (±25.0%) |
| e3-mongo@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +91.1% (±25.0%) |
| e3-mongo@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +48.9% (±25.0%) |
| e3-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +78.0% (±25.0%) |


### e3-mongo@1k

E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.

E3, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 860.1/s | 1024 | 290.3/s | 34% |
| head | 780.4/s | 1024 | 263.4/s | 34% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 688.1/s | 368.1/s | 7362 | 6400 | 4325.4 ms | 8323.1 ms | 9895.9 ms | n/a | 10420.2 ms | 0.99 cores | kafka 56% · mongo 56% | 0.30 of 1 | server 0.99 | 5.73 | 147.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | generator-overload 6261 · http-504-wow.wait.timeout 139 |
| probe2-base | base | 516.1/s | 393.3/s | 7866 | 2456 | 4177.9 ms | 7798.8 ms | 9502.7 ms | n/a | 10420.2 ms | 1.00 cores | kafka 61% · mongo 57% | 0.33 of 1 | server 1.00 | 5.53 | 208.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | generator-overload 2335 · http-504-wow.wait.timeout 121 |
| probe3-base | base | 387.1/s | 379.7/s | 7594 | 148 | 3260.4 ms | 6914.0 ms | 8847.4 ms | n/a | 10485.8 ms | 1.00 cores | kafka 59% · mongo 59% | 0.33 of 1 | server 1.00 | 5.72 | 283.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | generator-overload 118 · http-504-wow.wait.timeout 30 |
| probe4-base | base | 290.3/s | 290.3/s | 5806 | 0 | 1114.1 ms | 3588.1 ms | 4620.3 ms | n/a | 6488.1 ms | 1.00 cores | kafka 54% · mongo 63% | 0.30 of 1 | server 1.00 | 7.47 | 323.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | - |
| probe1-head | head | 624.3/s | 319.0/s | 6380 | 6106 | 4751.4 ms | 8781.8 ms | 10027.0 ms | n/a | 10485.8 ms | 1.00 cores | kafka 59% · mongo 53% | 0.30 of 1 | server 1.00 | 6.64 | 148.6 KB | - | datasets 8 of 8 · default 6–25 of 50, lowered 2, pressed 13 s | generator-overload 5687 · http-504-wow.wait.timeout 419 |
| probe2-head | head | 468.2/s | 340.7/s | 6814 | 2550 | 4718.6 ms | 8519.7 ms | 9895.9 ms | n/a | 10616.8 ms | 1.00 cores | kafka 63% · mongo 54% | 0.34 of 1 | server 1.00 | 6.35 | 206.3 KB | - | datasets 8 of 8 · default 25–30 of 50, lowered 0, pressed 8 s | generator-overload 2339 · http-504-wow.wait.timeout 211 |
| probe3-head | head | 351.2/s | 295.8/s | 5916 | 1108 | 5210.1 ms | 9044.0 ms | 10223.6 ms | n/a | 10485.8 ms | 0.99 cores | kafka 59% · mongo 52% | 0.32 of 1 | server 0.99 | 7.11 | 240.4 KB | - | datasets 8 of 8 · default 6–25 of 50, lowered 2, pressed 15 s | generator-overload 722 · http-504-wow.wait.timeout 386 |
| probe4-head | head | 263.4/s | 263.4/s | 5267 | 0 | 1556.5 ms | 4390.9 ms | 5570.6 ms | n/a | 6979.6 ms | 0.99 cores | kafka 60% · mongo 58% | 0.28 of 1 | server 0.99 | 8.27 | 330.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 290.3/s | 290.3/s | 11612 | 0 | 39.2 ms | 138.2 ms | 230.4 ms | n/a | 411.6 ms | 0.96 cores | kafka 47% · mongo 82% | 0.15 of 1 | server 0.96 | 7.77 | 318.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r1-head | head | 263.4/s | 263.4/s | 10536 | 0 | 38.7 ms | 212.0 ms | 610.3 ms | n/a | 938.0 ms | 0.97 cores | kafka 47% · mongo 78% | 0.15 of 1 | server 0.97 | 8.41 | 340.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| r2-head | head | 263.4/s | 263.4/s | 10536 | 0 | 38.9 ms | 146.4 ms | 230.4 ms | n/a | 335.9 ms | 0.98 cores | kafka 44% · mongo 77% | 0.14 of 1 | server 0.98 | 8.33 | 344.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r2-base | base | 290.3/s | 290.3/s | 11612 | 0 | 48.6 ms | 266.2 ms | 399.4 ms | n/a | 667.6 ms | 0.94 cores | kafka 46% · mongo 77% | 0.16 of 1 | server 0.94 | 7.49 | 312.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r3-base | base | 290.3/s | 290.3/s | 11612 | 0 | 36.4 ms | 130.0 ms | 203.8 ms | n/a | 337.9 ms | 0.99 cores | kafka 46% · mongo 80% | 0.16 of 1 | server 0.99 | 7.74 | 326.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| r3-head | head | 263.4/s | 263.4/s | 10536 | 0 | 33.3 ms | 157.7 ms | 458.8 ms | n/a | 843.8 ms | 0.91 cores | kafka 44% · mongo 78% | 0.14 of 1 | server 0.91 | 8.11 | 346.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 1 s | - |
| e1-base | base | 263.4/s | 263.4/s | 10536 | 0 | 99.8 ms | 651.3 ms | 860.2 ms | n/a | 1114.1 ms | 0.77 cores | kafka 44% · mongo 67% | 0.15 of 1 | server 0.77 | 7.14 | 297.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e1-head | head | 263.4/s | 263.4/s | 10536 | 0 | 42.0 ms | 230.4 ms | 372.7 ms | n/a | 602.1 ms | 0.94 cores | kafka 46% · mongo 77% | 0.16 of 1 | server 0.94 | 8.23 | 337.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e2-head | head | 263.4/s | 263.4/s | 10536 | 0 | 56.6 ms | 288.8 ms | 421.9 ms | n/a | 688.1 ms | 0.99 cores | kafka 47% · mongo 76% | 0.17 of 1 | server 0.99 | 8.42 | 330.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |
| e2-base | base | 263.4/s | 263.4/s | 10536 | 0 | 46.8 ms | 161.8 ms | 319.5 ms | n/a | 487.4 ms | 0.99 cores | kafka 48% · mongo 79% | 0.18 of 1 | server 0.99 | 8.59 | 324.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.907 | 0.987 | 1.533 | 2.649 | - | 2.279 | 1.082 | 1.071 |
| 2 | 0.907 | 0.800 | 0.550 | 0.577 | - | 0.503 | 1.112 | 1.102 |
| 3 | 0.907 | 0.915 | 1.213 | 2.251 | - | 2.497 | 1.047 | 1.063 |

Pairs at one rate for both arms, 263.4/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.421 | 0.354 | 0.433 | - | 0.540 | 1.152 | 1.133 |
| 2 | 1.000 | 1.208 | 1.785 | 1.321 | - | 1.412 | 0.980 | 1.020 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-mongo@1k | head against base | cost | **more allocation** | head allocates ×1.08 the bytes per operation of base (median of the equal-rate pairs) |
| e3-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -28.8% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -29.3% (±25.0%) |
| e3-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 38.7 ms, p99 610.3 ms |
| e3-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -45.6% (±25.0%) |
| e3-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -36.3% (±25.0%) |
| e3-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 33.3 ms, p99 458.8 ms |
| e3-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -85.1% (±25.0%) |
| e3-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +25.4% (±25.0%) |
| e3-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -40.6% (±25.0%) |


### e3-pg@100k

E3 saga chain on PostgreSQL: head against baseline.

E3, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (66–74 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 590.5/s | 32 | 472.4/s | 80% |
| head | 717.5/s | 512 | 430.5/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 472.4/s | 472.4/s | 9448 | 0 | 335.9 ms | 880.6 ms | 1073.2 ms | n/a | 1204.2 ms | 0.81 cores | kafka 43% · postgres 73% | 0.36 of 2 | server 0.81 | 4.18 | 238.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 8 s | - |
| probe1-head | head | 574.0/s | 548.0/s | 10959 | 521 | 1843.2 ms | 4325.4 ms | 4849.7 ms | n/a | 5242.9 ms | 0.93 cores | kafka 45% · postgres 61% | 0.44 of 2 | server 0.93 | 3.62 | 246.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 16 s | generator-overload 521 |
| probe2-head | head | 430.5/s | 430.5/s | 8610 | 0 | 179.2 ms | 954.4 ms | 1130.5 ms | n/a | 1269.8 ms | 0.82 cores | kafka 42% · postgres 73% | 0.35 of 2 | server 0.82 | 4.58 | 260.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 6 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 472.4/s | 472.4/s | 18896 | 0 | 83.5 ms | 290.8 ms | 395.3 ms | n/a | 528.4 ms | 0.82 cores | kafka 49% · postgres 69% | 0.20 of 2 | server 0.82 | 4.24 | 231.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r1-head | head | 430.5/s | 430.5/s | 17220 | 0 | 38.4 ms | 247.8 ms | 321.5 ms | n/a | 401.4 ms | 0.83 cores | kafka 48% · postgres 69% | 0.18 of 2 | server 0.83 | 4.65 | 250.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| r2-head | head | 430.5/s | 430.5/s | 17220 | 0 | 64.5 ms | 452.6 ms | 618.5 ms | n/a | 774.1 ms | 0.79 cores | kafka 49% · postgres 72% | 0.19 of 2 | server 0.79 | 4.66 | 252.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 5 s | - |
| r2-base | base | 472.4/s | 472.4/s | 18896 | 0 | 54.5 ms | 238.6 ms | 315.4 ms | n/a | 452.6 ms | 0.76 cores | kafka 49% · postgres 75% | 0.20 of 2 | server 0.76 | 4.24 | 232.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| r3-base | base | 472.4/s | 472.4/s | 18896 | 0 | 137.2 ms | 725.0 ms | 905.2 ms | n/a | 1024.0 ms | 0.77 cores | kafka 51% · postgres 72% | 0.22 of 2 | server 0.77 | 4.24 | 231.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 5 s | - |
| r3-head | head | 430.5/s | 430.5/s | 17220 | 0 | 53.0 ms | 368.6 ms | 618.5 ms | n/a | 770.0 ms | 0.79 cores | kafka 48% · postgres 72% | 0.19 of 2 | server 0.79 | 4.64 | 252.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| e1-base | base | 430.5/s | 430.5/s | 17220 | 0 | 24.8 ms | 344.1 ms | 598.0 ms | n/a | 708.6 ms | 0.77 cores | kafka 49% · postgres 73% | 0.18 of 2 | server 0.77 | 4.62 | 242.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| e1-head | head | 430.5/s | 430.5/s | 17220 | 0 | 40.4 ms | 228.4 ms | 321.5 ms | n/a | 544.8 ms | 0.79 cores | kafka 49% · postgres 73% | 0.18 of 2 | server 0.79 | 4.65 | 251.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e2-head | head | 430.5/s | 430.5/s | 17220 | 0 | 40.7 ms | 202.8 ms | 288.8 ms | n/a | 454.7 ms | 0.80 cores | kafka 48% · postgres 73% | 0.18 of 2 | server 0.80 | 4.66 | 253.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e2-base | base | 430.5/s | 430.5/s | 17220 | 0 | 28.9 ms | 151.6 ms | 209.9 ms | n/a | 303.1 ms | 0.79 cores | kafka 49% · postgres 71% | 0.18 of 2 | server 0.79 | 4.64 | 238.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.911 | 0.460 | 0.852 | 0.813 | - | 0.760 | 1.098 | 1.081 |
| 2 | 0.911 | 1.183 | 1.897 | 1.961 | - | 1.710 | 1.100 | 1.088 |
| 3 | 0.911 | 0.386 | 0.508 | 0.683 | - | 0.752 | 1.095 | 1.094 |

Pairs at one rate for both arms, 430.5/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.629 | 0.664 | 0.538 | - | 0.769 | 1.007 | 1.037 |
| 2 | 1.000 | 1.407 | 1.338 | 1.376 | - | 1.500 | 1.006 | 1.063 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -82.6% (±25.0%) |
| e3-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +78.5% (±25.0%) |
| e3-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -73.0% (±25.0%) |
| e3-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -41.4% (±25.0%) |
| e3-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -39.9% (±25.0%) |
| e3-pg@100k | r3-head | steady | **p99 > 10× p50** | p50 53.0 ms, p99 618.5 ms |
| e3-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -27.4% (±25.0%) |
| e3-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 24.8 ms, p99 598.0 ms |
| e3-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -33.3% (±25.0%) |
| e3-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -65.4% (±25.0%) |


### e3-pg@1k

E3 saga chain on PostgreSQL: head against baseline.

E3, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 578.4/s | 1024 | 195.2/s | 34% |
| head | 583.8/s | 2048 | 197.0/s | 34% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 462.7/s | 232.2/s | 4644 | 4610 | 6225.9 ms | 9895.9 ms | 10616.8 ms | n/a | 11051.5 ms | 1.13 cores | kafka 66% · postgres 21% | 0.32 of 2 | server 1.13 | 8.60 | 135.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 14 s | generator-overload 3931 · http-504-wow.wait.timeout 679 |
| probe2-base | base | 347.0/s | 186.8/s | 3736 | 3204 | 5931.0 ms | 9961.5 ms | 10551.3 ms | n/a | 11141.1 ms | 1.12 cores | kafka 67% · postgres 20% | 0.29 of 2 | server 1.12 | 10.67 | 159.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | generator-overload 1871 · http-504-wow.wait.timeout 1333 |
| probe3-base | base | 260.3/s | 194.4/s | 3888 | 1318 | 6488.1 ms | 10027.0 ms | 10747.9 ms | n/a | 11206.7 ms | 1.14 cores | kafka 65% · postgres 20% | 0.29 of 2 | server 1.14 | 10.25 | 218.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 11 s | generator-overload 723 · http-504-wow.wait.timeout 595 |
| probe4-base | base | 195.2/s | 194.9/s | 3899 | 5 | 3883.0 ms | 7176.2 ms | 8716.3 ms | n/a | 9830.4 ms | 1.13 cores | kafka 67% · postgres 22% | 0.28 of 2 | server 1.13 | 10.40 | 294.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | http-504-wow.wait.timeout 5 |
| probe1-head | head | 467.0/s | 203.9/s | 4079 | 5261 | 6848.5 ms | 10158.1 ms | 10813.4 ms | n/a | 11610.5 ms | 1.15 cores | kafka 63% · postgres 20% | 0.29 of 2 | server 1.15 | 9.71 | 124.3 KB | - | datasets 8 of 8 · default 2–5 of 5, lowered 1, pressed 10 s | generator-overload 4372 · http-504-wow.wait.timeout 889 |
| probe2-head | head | 350.3/s | 185.0/s | 3700 | 3306 | 6651.9 ms | 10158.1 ms | 10747.9 ms | n/a | 11272.2 ms | 1.17 cores | kafka 61% · postgres 20% | 0.29 of 2 | server 1.17 | 10.73 | 163.2 KB | - | datasets 8 of 8 · default 1–3 of 5, lowered 2, pressed 11 s | generator-overload 2199 · http-504-wow.wait.timeout 1107 |
| probe3-head | head | 262.7/s | 193.2/s | 3864 | 1390 | 6455.3 ms | 10092.5 ms | 10682.4 ms | n/a | 11373.0 ms | 1.16 cores | kafka 63% · postgres 21% | 0.25 of 2 | server 1.16 | 10.35 | 225.9 KB | - | datasets 8 of 8 · default 1–4 of 5, lowered 2, pressed 14 s | http-504-wow.wait.timeout 757 · generator-overload 633 |
| probe4-head | head | 197.0/s | 196.3/s | 3925 | 15 | 4489.2 ms | 7897.1 ms | 9633.8 ms | n/a | 10616.8 ms | 1.15 cores | kafka 66% · postgres 22% | 0.25 of 2 | server 1.15 | 10.32 | 305.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | http-504-wow.wait.timeout 15 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 195.2/s | 195.2/s | 7808 | 0 | 88.6 ms | 1941.5 ms | 2703.4 ms | n/a | 4112.4 ms | 1.01 cores | kafka 53% · postgres 46% | 0.20 of 2 | server 1.01 | 10.26 | 286.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 2 s | - |
| r1-head | head | 197.0/s | 197.0/s | 7880 | 0 | 228.4 ms | 2326.5 ms | 3309.6 ms | n/a | 4685.8 ms | 1.03 cores | kafka 54% · postgres 43% | 0.19 of 2 | server 1.03 | 10.16 | 297.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r2-head | head | 197.0/s | 197.0/s | 7880 | 0 | 89.1 ms | 1081.3 ms | 1482.8 ms | n/a | 2146.3 ms | 1.02 cores | kafka 56% · postgres 45% | 0.18 of 2 | server 1.02 | 10.27 | 288.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 2 s | - |
| r2-base | base | 195.2/s | 195.2/s | 7808 | 0 | 45.8 ms | 581.6 ms | 819.2 ms | n/a | 1261.6 ms | 1.00 cores | kafka 55% · postgres 46% | 0.18 of 2 | server 1.00 | 10.30 | 270.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| r3-base | base | 195.2/s | 195.2/s | 7808 | 0 | 68.6 ms | 757.8 ms | 1032.2 ms | n/a | 1679.4 ms | 1.01 cores | kafka 56% · postgres 45% | 0.18 of 2 | server 1.01 | 10.34 | 271.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r3-head | head | 197.0/s | 197.0/s | 7880 | 0 | 68.1 ms | 966.7 ms | 1351.7 ms | n/a | 1916.9 ms | 1.01 cores | kafka 55% · postgres 46% | 0.18 of 2 | server 1.01 | 10.26 | 291.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | - |
| e1-base | base | 195.2/s | 195.2/s | 7808 | 0 | 43.3 ms | 333.8 ms | 499.7 ms | n/a | 749.6 ms | 1.00 cores | kafka 55% · postgres 47% | 0.17 of 2 | server 1.00 | 10.34 | 271.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e1-head | head | 195.2/s | 195.2/s | 7808 | 0 | 64.0 ms | 358.4 ms | 491.5 ms | n/a | 630.8 ms | 1.01 cores | kafka 54% · postgres 46% | 0.18 of 2 | server 1.01 | 10.35 | 284.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| e2-head | head | 195.2/s | 195.2/s | 7808 | 0 | 106.5 ms | 1409.0 ms | 1974.3 ms | n/a | 2736.1 ms | 1.02 cores | kafka 55% · postgres 45% | 0.21 of 2 | server 1.02 | 10.35 | 292.5 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 2 s | - |
| e2-base | base | 195.2/s | 195.2/s | 7808 | 0 | 40.4 ms | 255.0 ms | 393.2 ms | n/a | 802.8 ms | 0.99 cores | kafka 55% · postgres 47% | 0.19 of 2 | server 0.99 | 10.31 | 273.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.009 | 2.578 | 1.198 | 1.224 | - | 1.139 | 0.990 | 1.036 |
| 2 | 1.009 | 1.944 | 1.859 | 1.810 | - | 1.701 | 0.997 | 1.067 |
| 3 | 1.009 | 0.993 | 1.276 | 1.310 | - | 1.141 | 0.992 | 1.076 |

Pairs at one rate for both arms, 195.2/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.479 | 1.074 | 0.984 | - | 0.842 | 1.001 | 1.050 |
| 2 | 1.000 | 2.633 | 5.526 | 5.021 | - | 3.408 | 1.004 | 1.068 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| e3-pg@1k | head against base | cost | **more allocation** | head allocates ×1.06 the bytes per operation of base (median of the equal-rate pairs) |
| e3-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -78.0% (±25.0%) |
| e3-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 88.6 ms, p99 2703.4 ms |
| e3-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -64.7% (±25.0%) |
| e3-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 228.4 ms, p99 3309.6 ms |
| e3-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -91.9% (±25.0%) |
| e3-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 89.1 ms, p99 1482.8 ms |
| e3-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -75.4% (±25.0%) |
| e3-pg@1k | r2-base | steady | **p99 > 10× p50** | p50 45.8 ms, p99 819.2 ms |
| e3-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -76.1% (±25.0%) |
| e3-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 68.6 ms, p99 1032.2 ms |
| e3-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -78.0% (±25.0%) |
| e3-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 68.1 ms, p99 1351.7 ms |
| e3-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -82.4% (±25.0%) |
| e3-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 43.3 ms, p99 499.7 ms |
| e3-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -81.3% (±25.0%) |
| e3-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.0% (±25.0%) |
| e3-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 106.5 ms, p99 1974.3 ms |
| e3-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -79.3% (±25.0%) |


### mixed-mongo@100k

Reads and writes together on MongoDB (E1 deposits 50%, E4 point reads 40%, E4 lists 10%): whether the query bulkheads keep the writes fast. Head against baseline.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (61–67 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2044.5/s | 256 | 1226.7/s | 60% |
| head | 1912.1/s | 256 | 1147.3/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1635.6/s | 1491.5/s | 29829 | 2883 | 561.2 ms | 1949.7 ms | 2359.3 ms | n/a | 2588.7 ms | 0.97 cores | kafka 50% · mongo 95% | 0.62 of 1 | server 0.97 | 1.63 | 86.5 KB | 2825 | datasets 8 of 8 · default 1–28 of 50, lowered 6, pressed 17 s | generator-overload 58 |
| probe2-base | base | 1226.7/s | 1216.9/s | 24338 | 196 | 34.8 ms | 181.2 ms | 247.8 ms | n/a | 288.8 ms | 0.94 cores | kafka 47% · mongo 92% | 0.55 of 1 | server 0.94 | 1.92 | 94.2 KB | 196 | datasets 8 of 8 · default 2–17 of 50, lowered 1, pressed 5 s | - |
| probe1-head | head | 1529.7/s | 1432.0/s | 28639 | 1955 | 86.5 ms | 536.6 ms | 745.5 ms | n/a | 864.3 ms | 0.97 cores | kafka 46% · mongo 95% | 0.58 of 1 | server 0.97 | 1.66 | 88.8 KB | 1955 | datasets 8 of 8 · default 1–7 of 50, lowered 5, pressed 15 s | - |
| probe2-head | head | 1147.3/s | 1147.3/s | 22946 | 0 | 22.3 ms | 105.5 ms | 172.0 ms | n/a | 240.6 ms | 0.90 cores | kafka 52% · mongo 91% | 0.52 of 1 | mongo 0.91 | 2.03 | 97.8 KB | - | datasets 8 of 8 · default 8–25 of 50, lowered 0, pressed 0 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1226.7/s | 1226.7/s | 49068 | 0 | 19.2 ms | 98.3 ms | 180.2 ms | n/a | 399.4 ms | 0.79 cores | kafka 49% · mongo 96% | 0.36 of 1 | mongo 0.96 | 1.83 | 97.3 KB | - | datasets 8 of 8 · default 19–25 of 50, lowered 0, pressed 2 s | - |
| r1-head | head | 1147.3/s | 1147.3/s | 45892 | 0 | 17.0 ms | 89.6 ms | 169.0 ms | n/a | 374.8 ms | 0.77 cores | kafka 47% · mongo 96% | 0.34 of 1 | mongo 0.96 | 1.91 | 99.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |
| r2-head | head | 1147.3/s | 1147.3/s | 45892 | 0 | 23.4 ms | 139.3 ms | 234.5 ms | n/a | 395.3 ms | 0.82 cores | kafka 48% · mongo 97% | 0.33 of 1 | mongo 0.97 | 1.98 | 96.7 KB | - | datasets 8 of 8 · default 20–25 of 50, lowered 0, pressed 9 s | - |
| r2-base | base | 1226.7/s | 1226.7/s | 49068 | 0 | 23.2 ms | 108.5 ms | 173.1 ms | n/a | 329.7 ms | 0.82 cores | kafka 47% · mongo 97% | 0.35 of 1 | mongo 0.97 | 1.84 | 94.5 KB | - | datasets 8 of 8 · default 18–25 of 50, lowered 0, pressed 2 s | - |
| r3-base | base | 1226.7/s | 1226.7/s | 49068 | 0 | 26.0 ms | 121.9 ms | 201.7 ms | n/a | 317.4 ms | 0.84 cores | kafka 46% · mongo 97% | 0.35 of 1 | mongo 0.97 | 1.85 | 93.7 KB | - | datasets 8 of 8 · default 14–25 of 50, lowered 0, pressed 2 s | - |
| r3-head | head | 1147.3/s | 1147.3/s | 45892 | 0 | 21.6 ms | 121.3 ms | 201.7 ms | n/a | 309.2 ms | 0.80 cores | kafka 47% · mongo 97% | 0.34 of 1 | mongo 0.97 | 1.95 | 97.1 KB | - | datasets 8 of 8 · default 22–25 of 50, lowered 0, pressed 3 s | - |
| e1-base | base | 1147.3/s | 1147.3/s | 45892 | 0 | 18.0 ms | 86.5 ms | 141.3 ms | n/a | 238.6 ms | 0.78 cores | kafka 48% · mongo 95% | 0.34 of 1 | mongo 0.95 | 1.92 | 98.0 KB | - | datasets 8 of 8 · default 23–25 of 50, lowered 0, pressed 0 s | - |
| e1-head | head | 1147.3/s | 1147.3/s | 45892 | 0 | 19.2 ms | 98.3 ms | 179.2 ms | n/a | 309.2 ms | 0.78 cores | kafka 46% · mongo 96% | 0.32 of 1 | mongo 0.96 | 1.92 | 98.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| e2-head | head | 1147.3/s | 1147.3/s | 45892 | 0 | 22.0 ms | 161.8 ms | 325.6 ms | n/a | 606.2 ms | 0.83 cores | kafka 46% · mongo 94% | 0.34 of 1 | mongo 0.94 | 1.94 | 98.6 KB | - | datasets 8 of 8 · default 24–25 of 50, lowered 0, pressed 8 s | - |
| e2-base | base | 1147.3/s | 1147.3/s | 45892 | 0 | 18.9 ms | 82.4 ms | 138.2 ms | n/a | 259.1 ms | 0.82 cores | kafka 48% · mongo 95% | 0.34 of 1 | mongo 0.95 | 1.96 | 97.7 KB | - | datasets 8 of 8 · default 18–25 of 50, lowered 0, pressed 1 s | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E4-list p50 | E4-list p99 | E4-list failed | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 13.2 ms | 69.1 ms | 0 | 34.8 ms | 227.3 ms | 0 | 12.4 ms | 68.6 ms | 0 |
| r1-head | head | 11.3 ms | 93.7 ms | 0 | 27.3 ms | 209.9 ms | 0 | 10.8 ms | 87.6 ms | 0 |
| r2-head | head | 15.2 ms | 102.9 ms | 0 | 40.7 ms | 272.4 ms | 0 | 14.2 ms | 100.4 ms | 0 |
| r2-base | base | 15.1 ms | 58.1 ms | 0 | 40.2 ms | 213.0 ms | 0 | 14.4 ms | 56.6 ms | 0 |
| r3-base | base | 16.6 ms | 76.3 ms | 0 | 46.3 ms | 233.5 ms | 0 | 15.9 ms | 71.7 ms | 0 |
| r3-head | head | 14.3 ms | 124.9 ms | 0 | 37.9 ms | 231.4 ms | 0 | 13.6 ms | 121.9 ms | 0 |
| e1-base | base | 12.2 ms | 53.8 ms | 0 | 32.0 ms | 161.8 ms | 0 | 11.6 ms | 51.5 ms | 0 |
| e1-head | head | 13.0 ms | 87.0 ms | 0 | 32.4 ms | 214.0 ms | 0 | 12.1 ms | 88.6 ms | 0 |
| e2-head | head | 14.4 ms | 141.3 ms | 0 | 37.6 ms | 372.7 ms | 0 | 13.8 ms | 141.3 ms | 0 |
| e2-base | base | 12.7 ms | 72.7 ms | 0 | 31.5 ms | 162.8 ms | 0 | 12.0 ms | 71.7 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.935 | 0.887 | 0.911 | 0.937 | - | 0.938 | 1.048 | 1.023 |
| 2 | 0.935 | 1.011 | 1.283 | 1.355 | - | 1.199 | 1.074 | 1.023 |
| 3 | 0.935 | 0.833 | 0.996 | 1.000 | - | 0.974 | 1.059 | 1.036 |

Pairs at one rate for both arms, 1147.3/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.064 | 1.136 | 1.268 | - | 1.296 | 1.000 | 1.002 |
| 2 | 1.000 | 1.162 | 1.963 | 2.356 | - | 2.340 | 0.989 | 1.009 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -29.7% (±25.0%) |
| mixed-mongo@100k | r2-head | steady | **p99 > 10× p50** | p50 23.4 ms, p99 234.5 ms |
| mixed-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -31.6% (±25.0%) |
| mixed-mongo@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -45.6% (±25.0%) |
| mixed-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 22.0 ms, p99 325.6 ms |
| mixed-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -28.7% (±25.0%) |


### mixed-mongo@1k

Reads and writes together on MongoDB (E1 deposits 50%, E4 point reads 40%, E4 lists 10%): whether the query bulkheads keep the writes fast. Head against baseline.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1515.1/s | 512 | 511.4/s | 34% (**not sustained**) |
| head | 1538.4/s | 256 | 519.2/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1212.1/s | 681.3/s | 13625 | 10617 | 2179.1 ms | 6225.9 ms | 7471.1 ms | n/a | 9175.0 ms | 0.99 cores | kafka 50% · mongo 55% | 0.48 of 1 | server 0.99 | 3.00 | 57.0 KB | 1207 | datasets 8 of 8 · default 2–7 of 50, lowered 3, pressed 9 s | generator-overload 9410 |
| probe2-base | base | 909.1/s | 604.1/s | 12083 | 6099 | 2457.6 ms | 6750.2 ms | 8257.5 ms | n/a | 9699.3 ms | 0.99 cores | kafka 55% · mongo 53% | 0.53 of 1 | server 0.99 | 3.43 | 68.7 KB | 1096 | datasets 8 of 8 · default 1–9 of 50, lowered 3, pressed 15 s | generator-overload 5003 |
| probe3-base | base | 681.8/s | 563.2/s | 11264 | 2372 | 2342.9 ms | 7569.4 ms | 9044.0 ms | n/a | 10485.8 ms | 0.99 cores | kafka 51% · mongo 53% | 0.42 of 1 | server 0.99 | 3.61 | 86.1 KB | 812 | datasets 8 of 8 · default 2–7 of 50, lowered 2, pressed 10 s | generator-overload 1550 · E1:http-504-wow.wait.timeout 10 |
| probe4-base | base | 511.4/s | 479.8/s | 9595 | 633 | 749.6 ms | 5439.5 ms | 6881.3 ms | n/a | 8093.7 ms | 0.99 cores | kafka 58% · mongo 56% | 0.51 of 1 | server 0.99 | 4.44 | 114.4 KB | 633 | datasets 8 of 8 · default 1–5 of 50, lowered 3, pressed 10 s | - |
| probe1-head | head | 1230.7/s | 700.5/s | 14009 | 10605 | 2326.5 ms | 5996.5 ms | 7569.4 ms | n/a | 9830.4 ms | 0.99 cores | kafka 53% · mongo 58% | 0.51 of 1 | server 0.99 | 3.01 | 59.1 KB | 1221 | datasets 8 of 8 · default 1–7 of 50, lowered 3, pressed 13 s | generator-overload 9383 · E1:http-504-wow.wait.timeout 1 |
| probe2-head | head | 923.0/s | 691.0/s | 13819 | 4641 | 2293.8 ms | 5898.2 ms | 7274.5 ms | n/a | 8454.1 ms | 0.99 cores | kafka 55% · mongo 56% | 0.52 of 1 | server 0.99 | 3.04 | 77.8 KB | 1150 | datasets 8 of 8 · default 1–10 of 50, lowered 3, pressed 12 s | generator-overload 3491 |
| probe3-head | head | 692.3/s | 624.9/s | 12498 | 1348 | 1187.8 ms | 5308.4 ms | 6651.9 ms | n/a | 8781.8 ms | 1.00 cores | kafka 54% · mongo 58% | 0.48 of 1 | server 1.00 | 3.38 | 101.9 KB | 1033 | datasets 8 of 8 · default 1–5 of 50, lowered 4, pressed 15 s | generator-overload 315 |
| probe4-head | head | 519.2/s | 503.9/s | 10079 | 305 | 80.9 ms | 3145.7 ms | 4325.4 ms | n/a | 5570.6 ms | 0.99 cores | kafka 56% · mongo 64% | 0.40 of 1 | server 0.99 | 4.35 | 122.3 KB | 305 | datasets 8 of 8 · default 1–11 of 50, lowered 2, pressed 6 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 511.4/s | 511.4/s | 20456 | 0 | 9.0 ms | 43.8 ms | 88.6 ms | n/a | 188.4 ms | 0.99 cores | kafka 58% · mongo 77% | 0.22 of 1 | server 0.99 | 4.56 | 128.6 KB | - | datasets 8 of 8 · default 18–25 of 50, lowered 0, pressed 0 s | - |
| r1-head | head | 519.2/s | 519.2/s | 20768 | 0 | 20.6 ms | 111.1 ms | 292.9 ms | n/a | 602.1 ms | 0.99 cores | kafka 55% · mongo 75% | 0.26 of 1 | server 0.99 | 4.43 | 122.3 KB | - | datasets 8 of 8 · default 11–25 of 50, lowered 0, pressed 1 s | - |
| r2-head | head | 519.2/s | 519.2/s | 20768 | 0 | 21.5 ms | 101.9 ms | 182.3 ms | n/a | 319.5 ms | 0.99 cores | kafka 53% · mongo 73% | 0.25 of 1 | server 0.99 | 4.35 | 123.6 KB | - | datasets 8 of 8 · default 7–25 of 50, lowered 0, pressed 0 s | - |
| r2-base | base | 511.4/s | 511.4/s | 20456 | 0 | 15.0 ms | 57.6 ms | 96.8 ms | n/a | 184.3 ms | 0.99 cores | kafka 55% · mongo 75% | 0.27 of 1 | server 0.99 | 4.48 | 125.0 KB | - | datasets 8 of 8 · default 10–25 of 50, lowered 0, pressed 0 s | - |
| r3-base | base | 511.4/s | 511.4/s | 20456 | 0 | 15.8 ms | 60.9 ms | 102.4 ms | n/a | 151.6 ms | 0.99 cores | kafka 57% · mongo 75% | 0.23 of 1 | server 0.99 | 4.52 | 124.9 KB | - | datasets 8 of 8 · default 6–25 of 50, lowered 0, pressed 1 s | - |
| r3-head | head | 519.2/s | 519.2/s | 20768 | 0 | 18.3 ms | 80.9 ms | 145.4 ms | n/a | 378.9 ms | 0.99 cores | kafka 55% · mongo 75% | 0.24 of 1 | server 0.99 | 4.42 | 126.8 KB | - | datasets 8 of 8 · default 11–25 of 50, lowered 0, pressed 1 s | - |
| e1-base | base | 511.4/s | 511.4/s | 20456 | 0 | 10.1 ms | 46.6 ms | 83.5 ms | n/a | 143.4 ms | 0.99 cores | kafka 56% · mongo 76% | 0.25 of 1 | server 0.99 | 4.53 | 126.7 KB | - | datasets 8 of 8 · default 14–25 of 50, lowered 0, pressed 0 s | - |
| e1-head | head | 511.4/s | 511.4/s | 20456 | 0 | 20.7 ms | 106.5 ms | 282.6 ms | n/a | 606.2 ms | 0.99 cores | kafka 54% · mongo 75% | 0.23 of 1 | server 0.99 | 4.45 | 125.8 KB | - | datasets 8 of 8 · default 5–25 of 50, lowered 1, pressed 2 s | - |
| e2-head | head | 511.4/s | 511.4/s | 20456 | 0 | 16.0 ms | 68.1 ms | 126.0 ms | n/a | 236.5 ms | 0.99 cores | kafka 55% · mongo 76% | 0.25 of 1 | server 0.99 | 4.49 | 126.5 KB | - | datasets 8 of 8 · default 7–25 of 50, lowered 0, pressed 0 s | - |
| e2-base | base | 511.4/s | 511.4/s | 20456 | 0 | 9.9 ms | 40.2 ms | 65.5 ms | n/a | 170.0 ms | 0.99 cores | kafka 56% · mongo 76% | 0.25 of 1 | server 0.99 | 4.53 | 127.5 KB | - | datasets 8 of 8 · default 12–25 of 50, lowered 0, pressed 0 s | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E4-list p50 | E4-list p99 | E4-list failed | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 7.3 ms | 67.1 ms | 0 | 13.2 ms | 116.7 ms | 0 | 6.3 ms | 68.6 ms | 0 |
| r1-head | head | 15.4 ms | 136.2 ms | 0 | 28.2 ms | 348.2 ms | 0 | 15.4 ms | 171.0 ms | 0 |
| r2-head | head | 15.8 ms | 127.0 ms | 0 | 29.2 ms | 206.8 ms | 0 | 15.8 ms | 117.2 ms | 0 |
| r2-base | base | 11.8 ms | 63.0 ms | 0 | 20.2 ms | 115.7 ms | 0 | 11.2 ms | 63.5 ms | 0 |
| r3-base | base | 12.4 ms | 70.1 ms | 0 | 21.2 ms | 116.7 ms | 0 | 11.8 ms | 72.7 ms | 0 |
| r3-head | head | 13.8 ms | 95.7 ms | 0 | 24.8 ms | 178.2 ms | 0 | 13.4 ms | 104.4 ms | 0 |
| e1-base | base | 8.0 ms | 52.7 ms | 0 | 14.3 ms | 95.7 ms | 0 | 7.4 ms | 56.6 ms | 0 |
| e1-head | head | 16.1 ms | 190.5 ms | 0 | 26.9 ms | 337.9 ms | 0 | 15.8 ms | 176.1 ms | 0 |
| e2-head | head | 12.1 ms | 99.3 ms | 0 | 22.5 ms | 143.4 ms | 0 | 11.6 ms | 92.2 ms | 0 |
| e2-base | base | 7.6 ms | 46.8 ms | 0 | 13.8 ms | 71.7 ms | 0 | 7.2 ms | 50.4 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.015 | 2.284 | 2.538 | 3.306 | - | 3.196 | 0.971 | 0.951 |
| 2 | 1.015 | 1.436 | 1.769 | 1.884 | - | 1.733 | 0.970 | 0.989 |
| 3 | 1.015 | 1.158 | 1.328 | 1.420 | - | 2.500 | 0.978 | 1.016 |

Pairs at one rate for both arms, 511.4/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 2.051 | 2.286 | 3.387 | - | 4.229 | 0.982 | 0.993 |
| 2 | 1.000 | 1.623 | 1.694 | 1.922 | - | 1.392 | 0.991 | 0.992 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -33.1% (±25.0%) |
| mixed-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -52.5% (±25.0%) |
| mixed-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 20.6 ms, p99 292.9 ms |
| mixed-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -64.8% (±25.0%) |
| mixed-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -39.3% (±25.0%) |
| mixed-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -52.9% (±25.0%) |
| mixed-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -60.7% (±25.0%) |
| mixed-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -66.2% (±25.0%) |
| mixed-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -47.2% (±25.0%) |
| mixed-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 20.7 ms, p99 282.6 ms |
| mixed-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -46.9% (±25.0%) |


### mixed-pg@100k

Reads and writes together on PostgreSQL, as mixed-mongo.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (67–77 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2187.8/s | 64 | 984.5/s | 45% |
| head | 2031.4/s | 64 | 914.1/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1750.2/s | 1661.7/s | 33234 | 1770 | 103.4 ms | 876.5 ms | 1278.0 ms | n/a | 1515.5 ms | 1.03 cores | kafka 41% · postgres 58% | 0.70 of 2 | server 1.03 | 1.21 | 75.8 KB | 1770 | datasets 8 of 8 · default 1–4 of 5, lowered 1, pressed 16 s | - |
| probe2-base | base | 1312.7/s | 1292.5/s | 25849 | 405 | 26.9 ms | 294.9 ms | 464.9 ms | n/a | 675.8 ms | 0.96 cores | kafka 45% · postgres 59% | 0.58 of 2 | server 0.96 | 1.54 | 72.7 KB | 405 | datasets 8 of 8 · default 1–5 of 5, lowered 2, pressed 8 s | - |
| probe3-base | base | 984.5/s | 984.5/s | 19690 | 0 | 5.1 ms | 34.6 ms | 59.4 ms | n/a | 88.1 ms | 0.90 cores | kafka 43% · postgres 54% | 0.48 of 2 | server 0.90 | 1.89 | 73.1 KB | - | datasets 8 of 8 · default 3–4 of 5, lowered 0, pressed 2 s | - |
| probe1-head | head | 1625.1/s | 1513.5/s | 30269 | 2233 | 352.3 ms | 1450.0 ms | 1990.7 ms | n/a | 2588.7 ms | 0.99 cores | kafka 51% · postgres 51% | 0.59 of 2 | server 0.99 | 1.33 | 76.9 KB | 2233 | datasets 8 of 8 · default 1 of 5, lowered 0, pressed 21 s | - |
| probe2-head | head | 1218.8/s | 1203.8/s | 24075 | 301 | 20.9 ms | 176.1 ms | 274.4 ms | n/a | 391.2 ms | 0.95 cores | kafka 46% · postgres 59% | 0.53 of 2 | server 0.95 | 1.66 | 74.3 KB | 301 | datasets 8 of 8 · default 1–5 of 5, lowered 2, pressed 9 s | - |
| probe3-head | head | 914.1/s | 914.1/s | 18282 | 0 | 3.7 ms | 17.4 ms | 36.9 ms | n/a | 157.7 ms | 0.92 cores | kafka 49% · postgres 44% | 0.48 of 2 | server 0.92 | 2.02 | 76.4 KB | - | datasets 8 of 8 · default 3–4 of 5, lowered 0, pressed 0 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 984.5/s | 984.5/s | 39380 | 0 | 3.2 ms | 19.1 ms | 53.8 ms | n/a | 145.4 ms | 0.77 cores | kafka 52% · postgres 49% | 0.35 of 2 | server 0.77 | 1.82 | 72.6 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 2 s | - |
| r1-head | head | 914.1/s | 914.1/s | 36564 | 0 | 2.4 ms | 11.8 ms | 29.4 ms | n/a | 162.8 ms | 0.73 cores | kafka 54% · postgres 45% | 0.32 of 2 | server 0.73 | 1.88 | 74.9 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | - |
| r2-head | head | 914.1/s | 914.1/s | 36564 | 0 | 2.3 ms | 11.3 ms | 38.1 ms | n/a | 162.8 ms | 0.74 cores | kafka 54% · postgres 44% | 0.31 of 2 | server 0.74 | 1.89 | 75.0 KB | - | datasets 8 of 8 · default 4–5 of 5, lowered 0, pressed 1 s | - |
| r2-base | base | 984.5/s | 984.5/s | 39380 | 0 | 3.3 ms | 17.8 ms | 64.3 ms | n/a | 274.4 ms | 0.77 cores | kafka 50% · postgres 49% | 0.38 of 2 | server 0.77 | 1.80 | 72.6 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 2 s | - |
| r3-base | base | 984.5/s | 984.5/s | 39380 | 0 | 2.7 ms | 13.9 ms | 46.1 ms | n/a | 164.9 ms | 0.76 cores | kafka 50% · postgres 48% | 0.35 of 2 | server 0.76 | 1.77 | 72.6 KB | - | datasets 8 of 8 · default 4–5 of 5, lowered 0, pressed 1 s | - |
| r3-head | head | 914.1/s | 914.1/s | 36563 | 1 | 2.0 ms | 11.9 ms | 43.8 ms | n/a | 196.6 ms | 0.73 cores | kafka 51% · postgres 47% | 0.31 of 2 | server 0.73 | 1.86 | 74.8 KB | 1 | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | - |
| e1-base | base | 914.1/s | 914.1/s | 36564 | 0 | 2.8 ms | 14.9 ms | 33.3 ms | n/a | 124.4 ms | 0.74 cores | kafka 53% · postgres 49% | 0.36 of 2 | server 0.74 | 1.93 | 72.7 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | - |
| e1-head | head | 914.1/s | 914.1/s | 36564 | 0 | 2.9 ms | 18.0 ms | 72.2 ms | n/a | 190.5 ms | 0.77 cores | kafka 52% · postgres 45% | 0.35 of 2 | server 0.77 | 1.91 | 74.4 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 1 s | - |
| e2-head | head | 914.1/s | 914.1/s | 36564 | 0 | 3.0 ms | 18.6 ms | 48.9 ms | n/a | 177.2 ms | 0.76 cores | kafka 52% · postgres 49% | 0.37 of 2 | server 0.76 | 1.93 | 75.5 KB | - | datasets 8 of 8 · default 3–5 of 5, lowered 0, pressed 0 s | - |
| e2-base | base | 914.1/s | 914.1/s | 36564 | 0 | 2.8 ms | 14.0 ms | 29.1 ms | n/a | 150.5 ms | 0.74 cores | kafka 53% · postgres 49% | 0.32 of 2 | server 0.74 | 1.92 | 73.5 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E4-list p50 | E4-list p99 | E4-list failed | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 3.8 ms | 36.4 ms | 0 | 3.9 ms | 63.5 ms | 0 | 2.3 ms | 30.0 ms | 0 |
| r1-head | head | 2.9 ms | 20.6 ms | 0 | 2.9 ms | 37.9 ms | 0 | 1.7 ms | 17.7 ms | 0 |
| r2-head | head | 2.8 ms | 27.8 ms | 0 | 2.8 ms | 48.9 ms | 0 | 1.6 ms | 21.2 ms | 0 |
| r2-base | base | 4.0 ms | 45.3 ms | 0 | 4.1 ms | 90.6 ms | 0 | 2.4 ms | 30.2 ms | 0 |
| r3-base | base | 3.4 ms | 31.0 ms | 0 | 3.4 ms | 66.6 ms | 0 | 2.0 ms | 22.8 ms | 0 |
| r3-head | head | 2.5 ms | 33.5 ms | 1 | 2.5 ms | 62.7 ms | 0 | 1.4 ms | 24.3 ms | 0 |
| e1-base | base | 3.5 ms | 22.7 ms | 0 | 3.5 ms | 41.0 ms | 0 | 2.1 ms | 19.6 ms | 0 |
| e1-head | head | 3.6 ms | 56.6 ms | 0 | 3.6 ms | 91.6 ms | 0 | 2.2 ms | 39.9 ms | 0 |
| e2-head | head | 3.6 ms | 29.8 ms | 0 | 3.6 ms | 64.5 ms | 0 | 2.2 ms | 19.7 ms | 0 |
| e2-base | base | 3.6 ms | 23.0 ms | 0 | 3.5 ms | 36.1 ms | 0 | 2.1 ms | 17.8 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.928 | 0.747 | 0.617 | 0.548 | - | 1.120 | 1.033 | 1.031 |
| 2 | 0.928 | 0.699 | 0.637 | 0.594 | - | 0.593 | 1.049 | 1.033 |
| 3 | 0.928 | 0.754 | 0.857 | 0.950 | - | 1.193 | 1.055 | 1.030 |

Pairs at one rate for both arms, 914.1/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 1.040 | 1.210 | 2.169 | - | 1.531 | 0.989 | 1.024 |
| 2 | 1.000 | 1.051 | 1.324 | 1.683 | - | 1.177 | 1.008 | 1.027 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -27.3% (±25.0%) |
| mixed-pg@100k | r1-base | steady | **p99 > 10× p50** | p50 3.2 ms, p99 53.8 ms |
| mixed-pg@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +41.8% (±25.0%) |
| mixed-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +25.2% (±25.0%) |
| mixed-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -59.8% (±25.0%) |
| mixed-pg@100k | r2-base | steady | **p99 > 10× p50** | p50 3.3 ms, p99 64.3 ms |
| mixed-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -39.4% (±25.0%) |
| mixed-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +53.0% (±25.0%) |
| mixed-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +31.3% (±25.0%) |
| mixed-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +105.4% (±25.0%) |
| mixed-pg@100k | e1-head | steady | **p99 > 10× p50** | p50 2.9 ms, p99 72.2 ms |
| mixed-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +39.4% (±25.0%) |
| mixed-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -48.1% (±25.0%) |


### mixed-pg@1k

Reads and writes together on PostgreSQL, as mixed-mongo.

mixed (E1:50,E4-state:40,E4-list:10), uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1379.8/s | 128 | 465.7/s | 34% (**not sustained**) |
| head | 1546.8/s | 256 | 522.0/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1103.8/s | 646.0/s | 12919 | 9157 | 3063.8 ms | 7110.7 ms | 8519.7 ms | n/a | 10158.1 ms | 1.22 cores | kafka 58% · postgres 17% | 0.39 of 2 | server 1.22 | 3.05 | 49.3 KB | 551 | datasets 8 of 8 · default 1–5 of 5, lowered 2, pressed 11 s | generator-overload 8605 · E1:http-504-wow.wait.timeout 1 |
| probe2-base | base | 827.8/s | 635.7/s | 12714 | 3842 | 2056.2 ms | 7110.7 ms | 8716.3 ms | n/a | 10289.2 ms | 1.22 cores | kafka 59% · postgres 16% | 0.40 of 2 | server 1.22 | 3.10 | 62.2 KB | 366 | datasets 8 of 8 · default 1–5 of 5, lowered 0, pressed 6 s | generator-overload 3470 · E1:http-504-wow.wait.timeout 6 |
| probe3-base | base | 620.9/s | 597.3/s | 11945 | 473 | 1204.2 ms | 5439.5 ms | 6586.4 ms | n/a | 8388.6 ms | 1.19 cores | kafka 60% · postgres 17% | 0.41 of 2 | server 1.19 | 3.28 | 83.1 KB | 222 | datasets 8 of 8 · default 1–5 of 5, lowered 0, pressed 11 s | generator-overload 251 |
| probe4-base | base | 465.7/s | 460.8/s | 9216 | 98 | 68.1 ms | 4046.8 ms | 5144.6 ms | n/a | 6586.4 ms | 1.16 cores | kafka 60% · postgres 20% | 0.35 of 2 | server 1.16 | 4.26 | 91.6 KB | 98 | datasets 8 of 8 · default 1–3 of 5, lowered 1, pressed 12 s | - |
| probe1-head | head | 1237.4/s | 579.0/s | 11580 | 13168 | 2654.2 ms | 7995.4 ms | 9568.3 ms | n/a | 11141.1 ms | 1.21 cores | kafka 61% · postgres 15% | 0.40 of 2 | server 1.21 | 3.41 | 39.9 KB | 420 | datasets 8 of 8 · default 1–4 of 5, lowered 1, pressed 12 s | generator-overload 12739 · E1:http-504-wow.wait.timeout 9 |
| probe2-head | head | 928.1/s | 616.4/s | 12327 | 6235 | 2424.8 ms | 7209.0 ms | 8781.8 ms | n/a | 10747.9 ms | 1.23 cores | kafka 57% · postgres 17% | 0.39 of 2 | server 1.23 | 3.19 | 56.2 KB | 460 | datasets 8 of 8 · default 1–5 of 5, lowered 1, pressed 13 s | generator-overload 5767 · E1:http-504-wow.wait.timeout 8 |
| probe3-head | head | 696.0/s | 594.6/s | 11892 | 2028 | 2474.0 ms | 7143.4 ms | 8781.8 ms | n/a | 10223.6 ms | 1.23 cores | kafka 57% · postgres 16% | 0.37 of 2 | server 1.23 | 3.30 | 70.5 KB | 330 | datasets 8 of 8 · default 1–4 of 5, lowered 1, pressed 15 s | generator-overload 1689 · E1:http-504-wow.wait.timeout 9 |
| probe4-head | head | 522.0/s | 498.9/s | 9977 | 463 | 1720.3 ms | 5931.0 ms | 7307.3 ms | n/a | 9371.6 ms | 1.20 cores | kafka 61% · postgres 15% | 0.38 of 2 | server 1.20 | 3.93 | 87.5 KB | 410 | datasets 8 of 8 · default 1–5 of 5, lowered 1, pressed 11 s | generator-overload 53 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 465.7/s | 465.7/s | 18628 | 0 | 6.1 ms | 89.1 ms | 532.5 ms | n/a | 1146.9 ms | 1.15 cores | kafka 57% · postgres 26% | 0.20 of 2 | server 1.15 | 4.25 | 83.1 KB | - | datasets 8 of 8 · default 4 of 5, lowered 0, pressed 2 s | - |
| r1-head | head | 522.0/s | 522.0/s | 20880 | 0 | 8.1 ms | 383.0 ms | 749.6 ms | n/a | 1318.9 ms | 1.15 cores | kafka 56% · postgres 29% | 0.22 of 2 | server 1.15 | 3.82 | 84.8 KB | - | datasets 8 of 8 · default 4–5 of 5, lowered 0, pressed 5 s | - |
| r2-head | head | 522.0/s | 522.0/s | 20880 | 0 | 6.2 ms | 137.2 ms | 305.2 ms | n/a | 589.8 ms | 1.16 cores | kafka 54% · postgres 29% | 0.21 of 2 | server 1.16 | 3.80 | 83.9 KB | - | datasets 8 of 8 · default 3–5 of 5, lowered 0, pressed 0 s | - |
| r2-base | base | 465.7/s | 465.7/s | 18628 | 0 | 6.0 ms | 59.4 ms | 191.5 ms | n/a | 401.4 ms | 1.12 cores | kafka 58% · postgres 27% | 0.20 of 2 | server 1.12 | 4.24 | 81.9 KB | - | datasets 8 of 8 · default 4 of 5, lowered 0, pressed 1 s | - |
| r3-base | base | 465.7/s | 465.7/s | 18628 | 0 | 6.5 ms | 89.6 ms | 227.3 ms | n/a | 1957.9 ms | 1.16 cores | kafka 54% · postgres 27% | 0.20 of 2 | server 1.16 | 4.25 | 82.9 KB | - | datasets 8 of 8 · default 4–5 of 5, lowered 0, pressed 0 s | - |
| r3-head | head | 522.0/s | 522.0/s | 20880 | 0 | 11.2 ms | 222.2 ms | 647.2 ms | n/a | 1384.4 ms | 1.15 cores | kafka 55% · postgres 30% | 0.22 of 2 | server 1.15 | 3.83 | 85.4 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 3 s | - |
| e1-base | base | 465.7/s | 465.7/s | 18628 | 0 | 8.0 ms | 72.7 ms | 393.2 ms | n/a | 1007.6 ms | 1.18 cores | kafka 54% · postgres 28% | 0.19 of 2 | server 1.18 | 4.27 | 83.8 KB | - | datasets 8 of 8 · default 1–3 of 5, lowered 1, pressed 2 s | - |
| e1-head | head | 465.7/s | 465.7/s | 18628 | 0 | 6.7 ms | 64.8 ms | 224.3 ms | n/a | 987.1 ms | 1.17 cores | kafka 54% · postgres 27% | 0.20 of 2 | server 1.17 | 4.26 | 86.1 KB | - | datasets 8 of 8 · default 5 of 5, lowered 0, pressed 0 s | - |
| e2-head | head | 465.7/s | 465.7/s | 18628 | 0 | 4.9 ms | 33.8 ms | 257.0 ms | n/a | 585.7 ms | 1.13 cores | kafka 58% · postgres 26% | 0.20 of 2 | server 1.13 | 4.25 | 85.2 KB | - | datasets 8 of 8 · default 4 of 5, lowered 0, pressed 1 s | - |
| e2-base | base | 465.7/s | 465.7/s | 18628 | 0 | 4.8 ms | 44.3 ms | 215.0 ms | n/a | 518.1 ms | 1.13 cores | kafka 57% · postgres 26% | 0.19 of 2 | server 1.13 | 4.21 | 82.9 KB | - | datasets 8 of 8 · default 4 of 5, lowered 0, pressed 0 s | - |

Each scenario of the mix (the writes under the reads):

| Run | Arm | E4-list p50 | E4-list p99 | E4-list failed | E1 p50 | E1 p99 | E1 failed | E4-state p50 | E4-state p99 | E4-state failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 6.8 ms | 52.7 ms | 0 | 7.3 ms | 675.8 ms | 0 | 4.6 ms | 44.0 ms | 0 |
| r1-head | head | 8.0 ms | 67.1 ms | 0 | 11.7 ms | 880.6 ms | 0 | 6.0 ms | 56.3 ms | 0 |
| r2-head | head | 6.8 ms | 46.3 ms | 0 | 8.0 ms | 360.4 ms | 0 | 4.7 ms | 38.4 ms | 0 |
| r2-base | base | 6.8 ms | 52.2 ms | 0 | 7.0 ms | 240.6 ms | 0 | 4.5 ms | 44.3 ms | 0 |
| r3-base | base | 7.1 ms | 49.2 ms | 0 | 8.1 ms | 276.5 ms | 0 | 4.9 ms | 40.2 ms | 0 |
| r3-head | head | 10.6 ms | 92.7 ms | 0 | 19.1 ms | 876.5 ms | 0 | 7.6 ms | 75.3 ms | 0 |
| e1-base | base | 8.4 ms | 55.6 ms | 0 | 10.6 ms | 514.0 ms | 0 | 6.0 ms | 46.1 ms | 0 |
| e1-head | head | 7.2 ms | 47.6 ms | 0 | 8.4 ms | 317.4 ms | 0 | 5.0 ms | 37.9 ms | 0 |
| e2-head | head | 5.6 ms | 36.6 ms | 0 | 5.8 ms | 329.7 ms | 0 | 3.7 ms | 29.8 ms | 0 |
| e2-base | base | 5.5 ms | 44.3 ms | 0 | 5.7 ms | 288.8 ms | 0 | 3.7 ms | 33.0 ms | 0 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.121 | 1.325 | 4.299 | 1.408 | - | 1.150 | 0.899 | 1.021 |
| 2 | 1.121 | 1.043 | 2.310 | 1.594 | - | 1.469 | 0.897 | 1.024 |
| 3 | 1.121 | 1.716 | 2.480 | 2.847 | - | 0.707 | 0.902 | 1.030 |

Pairs at one rate for both arms, 465.7/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.829 | 0.891 | 0.570 | - | 0.980 | 0.996 | 1.027 |
| 2 | 1.000 | 1.020 | 0.763 | 1.195 | - | 1.130 | 1.008 | 1.028 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| mixed-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -54.6% (±25.0%) |
| mixed-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 6.1 ms, p99 532.5 ms |
| mixed-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -79.1% (±25.0%) |
| mixed-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 8.1 ms, p99 749.6 ms |
| mixed-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -45.3% (±25.0%) |
| mixed-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 6.2 ms, p99 305.2 ms |
| mixed-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -60.7% (±25.0%) |
| mixed-pg@1k | r2-base | steady | **p99 > 10× p50** | p50 6.0 ms, p99 191.5 ms |
| mixed-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -52.2% (±25.0%) |
| mixed-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 6.5 ms, p99 227.3 ms |
| mixed-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -75.8% (±25.0%) |
| mixed-pg@1k | r3-head | steady | **p99 > 10× p50** | p50 11.2 ms, p99 647.2 ms |
| mixed-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -48.7% (±25.0%) |
| mixed-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 8.0 ms, p99 393.2 ms |
| mixed-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -44.3% (±25.0%) |
| mixed-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 6.7 ms, p99 224.3 ms |
| mixed-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -38.9% (±25.0%) |
| mixed-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 4.9 ms, p99 257.0 ms |
| mixed-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -40.5% (±25.0%) |
| mixed-pg@1k | e2-base | steady | **p99 > 10× p50** | p50 4.8 ms, p99 215.0 ms |


### large-mongo@100k

E1 with a large payload on MongoDB (D224 note ⑥): five events of about 2 KB per command. Head against baseline.

E1-large, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (70–79 s), then waited until the nodes were quiet again (4–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1098.8/s | 2048 | 659.3/s | 60% |
| head | 1008.8/s | 256 | 605.3/s | 60% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 879.0/s | 842.8/s | 16855 | 725 | 1851.4 ms | 2867.2 ms | 3162.1 ms | n/a | 3588.1 ms | 0.99 cores | kafka 47% · mongo 96% | 0.46 of 1 | server 0.99 | 2.87 | 212.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 8 s | generator-overload 725 |
| probe2-base | base | 659.3/s | 659.3/s | 13186 | 0 | 68.6 ms | 290.8 ms | 438.3 ms | n/a | 569.3 ms | 0.76 cores | kafka 47% · mongo 96% | 0.45 of 1 | mongo 0.96 | 3.33 | 236.7 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| probe1-head | head | 807.0/s | 767.6/s | 15353 | 787 | 1933.3 ms | 3162.1 ms | 3637.2 ms | n/a | 4030.5 ms | 0.96 cores | kafka 55% · mongo 91% | 0.55 of 1 | server 0.96 | 3.15 | 213.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | generator-overload 787 |
| probe2-head | head | 605.3/s | 605.3/s | 12106 | 0 | 61.4 ms | 237.6 ms | 352.3 ms | n/a | 438.3 ms | 0.78 cores | kafka 52% · mongo 96% | 0.49 of 1 | mongo 0.96 | 3.74 | 240.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 659.3/s | 659.3/s | 26372 | 0 | 49.2 ms | 194.6 ms | 309.2 ms | n/a | 466.9 ms | 0.72 cores | kafka 52% · mongo 96% | 0.28 of 1 | mongo 0.96 | 3.34 | 238.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r1-head | head | 605.3/s | 605.3/s | 24212 | 0 | 50.7 ms | 231.4 ms | 319.5 ms | n/a | 475.1 ms | 0.68 cores | kafka 48% · mongo 95% | 0.25 of 1 | mongo 0.95 | 3.49 | 243.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |
| r2-head | head | 605.3/s | 605.3/s | 24212 | 0 | 54.5 ms | 258.0 ms | 401.4 ms | n/a | 593.9 ms | 0.70 cores | kafka 51% · mongo 96% | 0.25 of 1 | mongo 0.96 | 3.58 | 243.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r2-base | base | 659.3/s | 659.3/s | 26372 | 0 | 54.8 ms | 280.6 ms | 387.1 ms | n/a | 514.0 ms | 0.66 cores | kafka 48% · mongo 97% | 0.26 of 1 | mongo 0.97 | 3.19 | 238.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| r3-base | base | 659.3/s | 659.3/s | 26372 | 0 | 62.7 ms | 247.8 ms | 376.8 ms | n/a | 569.3 ms | 0.67 cores | kafka 48% · mongo 98% | 0.27 of 1 | mongo 0.98 | 3.24 | 236.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | - |
| r3-head | head | 605.3/s | 605.3/s | 24212 | 0 | 53.0 ms | 228.4 ms | 346.1 ms | n/a | 479.2 ms | 0.64 cores | kafka 50% · mongo 96% | 0.24 of 1 | mongo 0.96 | 3.47 | 244.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 6 s | - |
| e1-base | base | 605.3/s | 605.3/s | 24212 | 0 | 63.2 ms | 249.9 ms | 370.7 ms | n/a | 593.9 ms | 0.66 cores | kafka 51% · mongo 98% | 0.25 of 1 | mongo 0.98 | 3.53 | 238.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | - |
| e1-head | head | 605.3/s | 605.3/s | 24212 | 0 | 59.4 ms | 198.7 ms | 264.2 ms | n/a | 462.8 ms | 0.64 cores | kafka 50% · mongo 97% | 0.24 of 1 | mongo 0.97 | 3.47 | 241.5 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e2-head | head | 605.3/s | 605.3/s | 24212 | 0 | 39.2 ms | 171.0 ms | 264.2 ms | n/a | 505.9 ms | 0.69 cores | kafka 52% · mongo 95% | 0.25 of 1 | mongo 0.95 | 3.56 | 244.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e2-base | base | 605.3/s | 605.3/s | 24212 | 0 | 42.5 ms | 175.1 ms | 251.9 ms | n/a | 362.5 ms | 0.65 cores | kafka 48% · mongo 96% | 0.26 of 1 | mongo 0.96 | 3.47 | 241.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.918 | 1.031 | 1.189 | 1.033 | - | 1.018 | 1.043 | 1.019 |
| 2 | 0.918 | 0.995 | 0.920 | 1.037 | - | 1.155 | 1.124 | 1.019 |
| 3 | 0.918 | 0.845 | 0.921 | 0.918 | - | 0.842 | 1.071 | 1.032 |

Pairs at one rate for both arms, 605.3/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.939 | 0.795 | 0.713 | - | 0.779 | 0.984 | 1.015 |
| 2 | 1.000 | 0.922 | 0.977 | 1.049 | - | 1.395 | 1.025 | 1.012 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +38.1% (±25.0%) |
| large-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +64.5% (±25.0%) |
| large-mongo@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -52.1% (±25.0%) |
| large-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 +71.5% (±25.0%) |
| large-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 +123.1% (±25.0%) |
| large-mongo@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +42.0% (±25.0%) |


### large-mongo@1k

E1 with a large payload on MongoDB (D224 note ⑥): five events of about 2 KB per command. Head against baseline.

E1-large, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 2257.3/s | 1024 | 1015.8/s | 45% |
| head | 1709.9/s | 32 | 769.4/s | 45% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 1805.8/s | 1191.0/s | 23819 | 12297 | 1540.1 ms | 2605.1 ms | 3096.6 ms | n/a | 3358.7 ms | 0.99 cores | kafka 47% · mongo 49% | 0.40 of 1 | server 0.99 | 1.63 | 149.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | generator-overload 12297 |
| probe2-base | base | 1354.4/s | 1240.8/s | 24816 | 2272 | 1368.1 ms | 2310.1 ms | 2768.9 ms | n/a | 3080.2 ms | 1.00 cores | kafka 44% · mongo 50% | 0.38 of 1 | server 1.00 | 1.56 | 209.3 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 7 s | generator-overload 2272 |
| probe3-base | base | 1015.8/s | 1015.8/s | 20316 | 0 | 395.3 ms | 1523.7 ms | 2023.4 ms | n/a | 2523.1 ms | 1.00 cores | kafka 46% · mongo 58% | 0.36 of 1 | server 1.00 | 2.00 | 249.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| probe1-head | head | 1367.9/s | 1159.8/s | 23197 | 4161 | 1490.9 ms | 2523.1 ms | 2998.3 ms | n/a | 3506.2 ms | 1.00 cores | kafka 46% · mongo 49% | 0.41 of 1 | server 1.00 | 1.68 | 198.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 11 s | generator-overload 4161 |
| probe2-head | head | 1025.9/s | 1014.5/s | 20290 | 228 | 1286.1 ms | 2474.0 ms | 2932.7 ms | n/a | 3473.4 ms | 1.00 cores | kafka 47% · mongo 51% | 0.38 of 1 | server 1.00 | 1.95 | 244.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 9 s | generator-overload 228 |
| probe3-head | head | 769.4/s | 769.4/s | 15388 | 0 | 124.4 ms | 1048.6 ms | 1499.1 ms | n/a | 1908.7 ms | 1.00 cores | kafka 49% · mongo 60% | 0.30 of 1 | server 1.00 | 2.71 | 263.1 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 5 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 1015.8/s | 1015.8/s | 40632 | 0 | 31.6 ms | 444.4 ms | 770.0 ms | n/a | 1146.9 ms | 0.97 cores | kafka 42% · mongo 73% | 0.25 of 1 | server 0.97 | 2.08 | 242.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r1-head | head | 769.4/s | 769.4/s | 30776 | 0 | 20.9 ms | 593.9 ms | 1003.5 ms | n/a | 1441.8 ms | 0.95 cores | kafka 44% · mongo 74% | 0.22 of 1 | server 0.95 | 2.77 | 250.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r2-head | head | 769.4/s | 769.4/s | 30776 | 0 | 24.8 ms | 423.9 ms | 766.0 ms | n/a | 1146.9 ms | 0.98 cores | kafka 43% · mongo 70% | 0.21 of 1 | server 0.98 | 2.75 | 251.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r2-base | base | 1015.8/s | 1015.8/s | 40632 | 0 | 51.7 ms | 520.2 ms | 843.8 ms | n/a | 1261.6 ms | 0.98 cores | kafka 45% · mongo 71% | 0.27 of 1 | server 0.98 | 2.11 | 237.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r3-base | base | 1015.8/s | 1015.8/s | 40632 | 0 | 48.9 ms | 569.3 ms | 921.6 ms | n/a | 1278.0 ms | 0.99 cores | kafka 43% · mongo 71% | 0.25 of 1 | server 0.99 | 2.10 | 238.8 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| r3-head | head | 769.4/s | 769.4/s | 30776 | 0 | 22.8 ms | 337.9 ms | 659.5 ms | n/a | 991.2 ms | 0.98 cores | kafka 43% · mongo 72% | 0.21 of 1 | server 0.98 | 2.77 | 256.6 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e1-base | base | 769.4/s | 769.4/s | 30776 | 0 | 15.3 ms | 335.9 ms | 626.7 ms | n/a | 929.8 ms | 0.96 cores | kafka 44% · mongo 74% | 0.21 of 1 | server 0.96 | 2.78 | 254.0 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 4 s | - |
| e1-head | head | 769.4/s | 769.4/s | 30776 | 0 | 15.0 ms | 378.9 ms | 737.3 ms | n/a | 1171.5 ms | 0.96 cores | kafka 41% · mongo 72% | 0.20 of 1 | server 0.96 | 2.72 | 260.9 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e2-head | head | 769.4/s | 769.4/s | 30776 | 0 | 19.6 ms | 450.6 ms | 831.5 ms | n/a | 1237.0 ms | 0.93 cores | kafka 42% · mongo 71% | 0.21 of 1 | server 0.93 | 2.68 | 253.2 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 3 s | - |
| e2-base | base | 769.4/s | 769.4/s | 30776 | 0 | 13.9 ms | 292.9 ms | 622.6 ms | n/a | 1007.6 ms | 0.97 cores | kafka 41% · mongo 74% | 0.21 of 1 | server 0.97 | 2.75 | 257.4 KB | - | datasets 8 of 8 · default 25 of 50, lowered 0, pressed 2 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.757 | 0.660 | 1.336 | 1.303 | - | 1.257 | 1.331 | 1.031 |
| 2 | 0.757 | 0.480 | 0.815 | 0.908 | - | 0.909 | 1.305 | 1.061 |
| 3 | 0.757 | 0.466 | 0.594 | 0.716 | - | 0.776 | 1.321 | 1.074 |

Pairs at one rate for both arms, 769.4/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.983 | 1.128 | 1.176 | - | 1.260 | 0.977 | 1.027 |
| 2 | 1.000 | 1.410 | 1.538 | 1.336 | - | 1.228 | 0.973 | 0.984 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-mongo@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -68.6% (±25.0%) |
| large-mongo@1k | r1-base | steady | **p99 > 10× p50** | p50 31.6 ms, p99 770.0 ms |
| large-mongo@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -49.9% (±25.0%) |
| large-mongo@1k | r1-head | steady | **p99 > 10× p50** | p50 20.9 ms, p99 1003.5 ms |
| large-mongo@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -27.2% (±25.0%) |
| large-mongo@1k | r2-head | steady | **p99 > 10× p50** | p50 24.8 ms, p99 766.0 ms |
| large-mongo@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -78.6% (±25.0%) |
| large-mongo@1k | r2-base | steady | **p99 > 10× p50** | p50 51.7 ms, p99 843.8 ms |
| large-mongo@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -63.5% (±25.0%) |
| large-mongo@1k | r3-base | steady | **p99 > 10× p50** | p50 48.9 ms, p99 921.6 ms |
| large-mongo@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -56.9% (±25.0%) |
| large-mongo@1k | r3-head | steady | **p99 > 10× p50** | p50 22.8 ms, p99 659.5 ms |
| large-mongo@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -62.9% (±25.0%) |
| large-mongo@1k | e1-base | steady | **p99 > 10× p50** | p50 15.3 ms, p99 626.7 ms |
| large-mongo@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -51.7% (±25.0%) |
| large-mongo@1k | e1-head | steady | **p99 > 10× p50** | p50 15.0 ms, p99 737.3 ms |
| large-mongo@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -31.9% (±25.0%) |
| large-mongo@1k | e2-head | steady | **p99 > 10× p50** | p50 19.6 ms, p99 831.5 ms |
| large-mongo@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -41.9% (±25.0%) |
| large-mongo@1k | e2-base | steady | **p99 > 10× p50** | p50 13.9 ms, p99 622.6 ms |


### large-pg@100k

E1 with a large payload on PostgreSQL, as large-mongo.

E1-large, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (47–58 s), then waited until the nodes were quiet again (3–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 813.8/s | 8 | 651.0/s | 80% |
| head | 955.5/s | 64 | 764.4/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 651.0/s | 651.0/s | 13020 | 0 | 11.0 ms | 246.8 ms | 464.9 ms | n/a | 651.3 ms | 0.59 cores | kafka 46% · postgres 58% | 0.27 of 2 | server 0.59 | 2.50 | 240.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| probe1-head | head | 764.4/s | 764.4/s | 15288 | 0 | 59.1 ms | 331.8 ms | 462.8 ms | n/a | 565.2 ms | 0.63 cores | kafka 46% · postgres 64% | 0.32 of 2 | postgres 0.64 | 2.26 | 239.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 651.0/s | 651.0/s | 26040 | 0 | 13.4 ms | 544.8 ms | 958.5 ms | n/a | 1302.5 ms | 0.54 cores | kafka 47% · postgres 53% | 0.19 of 2 | server 0.54 | 2.37 | 239.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 7 s | - |
| r1-head | head | 764.4/s | 764.4/s | 30576 | 0 | 132.1 ms | 663.6 ms | 958.5 ms | n/a | 1294.3 ms | 0.56 cores | kafka 48% · postgres 56% | 0.21 of 2 | postgres 0.56 | 2.09 | 239.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 16 s | - |
| r2-head | head | 764.4/s | 764.4/s | 30576 | 0 | 113.7 ms | 561.2 ms | 798.7 ms | n/a | 1081.3 ms | 0.58 cores | kafka 45% · postgres 58% | 0.23 of 2 | postgres 0.58 | 2.10 | 238.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 14 s | - |
| r2-base | base | 651.0/s | 651.0/s | 26040 | 0 | 223.2 ms | 933.9 ms | 1278.0 ms | n/a | 1630.2 ms | 0.52 cores | kafka 46% · postgres 48% | 0.19 of 2 | server 0.52 | 2.25 | 233.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 23 s | - |
| r3-base | base | 651.0/s | 651.0/s | 26040 | 0 | 64.5 ms | 561.2 ms | 745.5 ms | n/a | 892.9 ms | 0.49 cores | kafka 45% · postgres 51% | 0.18 of 2 | postgres 0.51 | 2.22 | 235.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | - |
| r3-head | head | 764.4/s | 764.4/s | 30576 | 0 | 160.8 ms | 753.7 ms | 995.3 ms | n/a | 1163.3 ms | 0.56 cores | kafka 46% · postgres 59% | 0.21 of 2 | postgres 0.59 | 2.12 | 240.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 16 s | - |
| e1-base | base | 651.0/s | 651.0/s | 26040 | 0 | 12.1 ms | 309.2 ms | 444.4 ms | n/a | 561.2 ms | 0.58 cores | kafka 49% · postgres 53% | 0.19 of 2 | server 0.58 | 2.46 | 240.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 4 s | - |
| e1-head | head | 651.0/s | 651.0/s | 26040 | 0 | 88.1 ms | 581.6 ms | 819.2 ms | n/a | 1171.5 ms | 0.50 cores | kafka 46% · postgres 54% | 0.20 of 2 | postgres 0.54 | 2.30 | 238.4 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 18 s | - |
| e2-head | head | 651.0/s | 651.0/s | 26040 | 0 | 78.3 ms | 655.4 ms | 946.2 ms | n/a | 1220.6 ms | 0.52 cores | kafka 46% · postgres 53% | 0.19 of 2 | postgres 0.53 | 2.33 | 238.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 15 s | - |
| e2-base | base | 651.0/s | 651.0/s | 26040 | 0 | 94.7 ms | 622.6 ms | 856.1 ms | n/a | 1081.3 ms | 0.49 cores | kafka 48% · postgres 51% | 0.19 of 2 | postgres 0.51 | 2.27 | 236.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 17 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.174 | 9.876 | 1.218 | 1.000 | - | 0.994 | 0.881 | 0.999 |
| 2 | 1.174 | 0.509 | 0.601 | 0.625 | - | 0.663 | 0.932 | 1.019 |
| 3 | 1.174 | 2.492 | 1.343 | 1.335 | - | 1.303 | 0.951 | 1.021 |

Pairs at one rate for both arms, 651.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 7.281 | 1.881 | 1.843 | - | 2.088 | 0.932 | 0.991 |
| 2 | 1.000 | 0.827 | 1.053 | 1.105 | - | 1.129 | 1.026 | 1.009 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-pg@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -90.3% (±25.0%) |
| large-pg@100k | r1-base | steady | **p99 > 10× p50** | p50 13.4 ms, p99 958.5 ms |
| large-pg@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 +42.5% (±25.0%) |
| large-pg@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +79.4% (±25.0%) |
| large-pg@100k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -31.1% (±25.0%) |
| large-pg@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -49.2% (±25.0%) |
| large-pg@100k | r3-base | steady | **p99 > 10× p50** | p50 64.5 ms, p99 745.5 ms |
| large-pg@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -58.1% (±25.0%) |
| large-pg@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 +43.2% (±25.0%) |
| large-pg@100k | e1-base | steady | **p99 > 10× p50** | p50 12.1 ms, p99 444.4 ms |
| large-pg@100k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -85.2% (±25.0%) |
| large-pg@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 +174.0% (±25.0%) |
| large-pg@100k | e2-head | steady | **p99 > 10× p50** | p50 78.3 ms, p99 946.2 ms |
| large-pg@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 +144.2% (±25.0%) |


### large-pg@1k

E1 with a large payload on PostgreSQL, as large-mongo.

E1-large, uniform keys over 1000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Not primed: 1000 accounts, below 100000.

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 891.9/s | 2048 | 301.0/s | 34% |
| head | 974.9/s | 2048 | 329.0/s | 34% (**not sustained**) |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 713.5/s | 365.3/s | 7306 | 6964 | 4980.7 ms | 7864.3 ms | 9437.2 ms | n/a | 10354.7 ms | 1.03 cores | kafka 63% · postgres 33% | 0.42 of 2 | server 1.03 | 5.45 | 128.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | generator-overload 6964 |
| probe2-base | base | 535.1/s | 387.1/s | 7743 | 2959 | 4587.5 ms | 7340.0 ms | 8716.3 ms | n/a | 10551.3 ms | 1.02 cores | kafka 64% · postgres 36% | 0.41 of 2 | server 1.02 | 5.22 | 180.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | generator-overload 2959 |
| probe3-base | base | 401.3/s | 365.0/s | 7300 | 726 | 4554.8 ms | 7176.2 ms | 8290.3 ms | n/a | 9699.3 ms | 1.02 cores | kafka 62% · postgres 36% | 0.37 of 2 | server 1.02 | 5.48 | 225.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | generator-overload 725 · http-504-wow.wait.timeout 1 |
| probe4-base | base | 301.0/s | 301.0/s | 6020 | 0 | 3162.1 ms | 5996.5 ms | 7209.0 ms | n/a | 8323.1 ms | 1.01 cores | kafka 65% · postgres 35% | 0.38 of 2 | server 1.01 | 6.69 | 299.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | - |
| probe1-head | head | 779.9/s | 362.1/s | 7242 | 8356 | 4915.2 ms | 7634.9 ms | 9240.6 ms | n/a | 10158.1 ms | 1.03 cores | kafka 61% · postgres 35% | 0.43 of 2 | server 1.03 | 5.50 | 117.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | generator-overload 8350 · http-504-wow.wait.timeout 6 |
| probe2-head | head | 584.9/s | 365.5/s | 7310 | 4388 | 4522.0 ms | 7766.0 ms | 9306.1 ms | n/a | 10551.3 ms | 1.05 cores | kafka 62% · postgres 35% | 0.41 of 2 | server 1.05 | 5.51 | 158.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 1 s | generator-overload 4155 · http-504-wow.wait.timeout 233 |
| probe3-head | head | 438.7/s | 353.1/s | 7063 | 1711 | 5210.1 ms | 8060.9 ms | 9502.7 ms | n/a | 10944.5 ms | 1.05 cores | kafka 63% · postgres 32% | 0.43 of 2 | server 1.05 | 5.63 | 198.2 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | generator-overload 1701 · http-504-wow.wait.timeout 10 |
| probe4-head | head | 329.0/s | 319.1/s | 6383 | 197 | 3915.8 ms | 7307.3 ms | 9764.9 ms | n/a | 10682.4 ms | 1.02 cores | kafka 65% · postgres 34% | 0.36 of 2 | server 1.02 | 6.32 | 280.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 3 s | generator-overload 152 · http-504-wow.wait.timeout 45 |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | wow.query.busy (bulkhead) | Query bound | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 301.0/s | 301.0/s | 12040 | 0 | 23.3 ms | 272.4 ms | 477.2 ms | n/a | 831.5 ms | 1.00 cores | kafka 53% · postgres 46% | 0.20 of 2 | server 1.00 | 6.64 | 256.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r1-head | head | 329.0/s | 329.0/s | 13160 | 0 | 113.2 ms | 3309.6 ms | 4390.9 ms | n/a | 5668.9 ms | 0.96 cores | kafka 56% · postgres 48% | 0.22 of 2 | server 0.96 | 6.10 | 273.3 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 11 s | - |
| r2-head | head | 329.0/s | 329.0/s | 13160 | 0 | 116.2 ms | 2179.1 ms | 3162.1 ms | n/a | 4259.8 ms | 0.95 cores | kafka 57% · postgres 49% | 0.20 of 2 | server 0.95 | 6.12 | 268.0 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 8 s | - |
| r2-base | base | 301.0/s | 301.0/s | 12040 | 0 | 23.2 ms | 135.2 ms | 214.0 ms | n/a | 370.7 ms | 1.02 cores | kafka 51% · postgres 47% | 0.18 of 2 | server 1.02 | 6.63 | 256.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 0 s | - |
| r3-base | base | 301.0/s | 301.0/s | 12040 | 0 | 142.3 ms | 2342.9 ms | 3326.0 ms | n/a | 4423.7 ms | 0.97 cores | kafka 58% · postgres 46% | 0.20 of 2 | server 0.97 | 6.67 | 269.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 13 s | - |
| r3-head | head | 329.0/s | 329.0/s | 13160 | 0 | 786.4 ms | 5570.6 ms | 6914.0 ms | n/a | 8781.8 ms | 0.98 cores | kafka 60% · postgres 42% | 0.22 of 2 | server 0.98 | 6.09 | 272.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 15 s | - |
| e1-base | base | 301.0/s | 301.0/s | 12040 | 0 | 126.0 ms | 1400.8 ms | 2244.6 ms | n/a | 3047.4 ms | 0.97 cores | kafka 57% · postgres 46% | 0.19 of 2 | server 0.97 | 6.65 | 263.1 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 10 s | - |
| e1-head | head | 301.0/s | 301.0/s | 12040 | 0 | 115.7 ms | 2572.3 ms | 3719.2 ms | n/a | 5275.6 ms | 0.99 cores | kafka 55% · postgres 46% | 0.21 of 2 | server 0.99 | 6.66 | 272.8 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 12 s | - |
| e2-head | head | 301.0/s | 301.0/s | 12040 | 0 | 52.5 ms | 2015.2 ms | 3031.0 ms | n/a | 4194.3 ms | 0.97 cores | kafka 56% · postgres 48% | 0.19 of 2 | server 0.97 | 6.68 | 269.7 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | - |
| e2-base | base | 301.0/s | 301.0/s | 12040 | 0 | 110.6 ms | 2506.8 ms | 3457.0 ms | n/a | 5111.8 ms | 0.97 cores | kafka 57% · postgres 46% | 0.20 of 2 | server 0.97 | 6.65 | 266.9 KB | - | datasets 8 of 8 · default 2 of 5, lowered 0, pressed 9 s | - |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.093 | 4.857 | 12.150 | 9.202 | - | 6.818 | 0.919 | 1.064 |
| 2 | 1.093 | 5.017 | 16.121 | 14.775 | - | 11.492 | 0.922 | 1.043 |
| 3 | 1.093 | 5.525 | 2.378 | 2.079 | - | 1.985 | 0.914 | 1.014 |

Pairs at one rate for both arms, 301.0/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.000 | 0.919 | 1.836 | 1.657 | - | 1.731 | 1.002 | 1.037 |
| 2 | 1.000 | 0.475 | 0.804 | 0.877 | - | 0.821 | 1.003 | 1.011 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-pg@1k | r1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -64.7% (±25.0%) |
| large-pg@1k | r1-base | steady | **p99 > 10× p50** | p50 23.3 ms, p99 477.2 ms |
| large-pg@1k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -94.1% (±25.0%) |
| large-pg@1k | r1-head | steady | **p99 > 10× p50** | p50 113.2 ms, p99 4390.9 ms |
| large-pg@1k | r2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -74.5% (±25.0%) |
| large-pg@1k | r2-head | steady | **p99 > 10× p50** | p50 116.2 ms, p99 3162.1 ms |
| large-pg@1k | r2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -80.1% (±25.0%) |
| large-pg@1k | r3-base | steady | **unstable** | second half against first, beyond the default limits: p50 -79.3% (±25.0%) |
| large-pg@1k | r3-base | steady | **p99 > 10× p50** | p50 142.3 ms, p99 3326.0 ms |
| large-pg@1k | r3-head | steady | **unstable** | second half against first, beyond the default limits: p50 -94.1% (±25.0%) |
| large-pg@1k | e1-base | steady | **unstable** | second half against first, beyond the default limits: p50 -76.1% (±25.0%) |
| large-pg@1k | e1-base | steady | **p99 > 10× p50** | p50 126.0 ms, p99 2244.6 ms |
| large-pg@1k | e1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -87.5% (±25.0%) |
| large-pg@1k | e1-head | steady | **p99 > 10× p50** | p50 115.7 ms, p99 3719.2 ms |
| large-pg@1k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -83.9% (±25.0%) |
| large-pg@1k | e2-head | steady | **p99 > 10× p50** | p50 52.5 ms, p99 3031.0 ms |
| large-pg@1k | e2-base | steady | **unstable** | second half against first, beyond the default limits: p50 -87.1% (±25.0%) |
| large-pg@1k | e2-base | steady | **p99 > 10× p50** | p50 110.6 ms, p99 3457.0 ms |



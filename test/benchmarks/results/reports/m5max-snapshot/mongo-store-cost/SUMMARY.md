# mongo-store-cost: measurements

| Measurement | Peak (failed 1% or less) or result | Sustainable | Failed more than 1% | Last step or provenance | Disturbed steps | Void steps | Generator-bound steps | Load before | Load after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-before/run | 7,995.6/s at 8,000/s (**not reached**), p50 9.41 ms, p99 190.5 ms, failed 531 | – | – | commit d9b0781205, dirty False, started 2026-11-12T07:21:21.563064Z | none | none | – | 11.41 8.69 5.86 | 8.46 9.01 6.53 |
| r1-d420/run | 8,000.0/s at 8,000/s (reached), p50 10.30 ms, p99 184.3 ms, failed 0 | – | – | commit d9b0781205, dirty False, started 2026-11-12T07:25:14.649252Z | none | none | – | 8.90 9.14 6.79 | 13.18 11.18 8.03 |
| r1-tag/run | 8,000.0/s at 8,000/s (reached), p50 7.78 ms, p99 111.1 ms, failed 0 | – | – | commit d9b0781205, dirty False, started 2026-11-12T07:17:31.371667Z | none | none | – | 5.12 3.82 3.66 | 14.92 8.18 5.44 |
| r2-before/run | 7,988.7/s at 8,000/s (**not reached**), p50 7.39 ms, p99 174.1 ms, failed 1359 | – | – | commit d9b0781205, dirty False, started 2026-11-12T07:32:57.120121Z | none | none | – | 11.76 11.52 9.06 | 11.34 11.65 9.56 |
| r2-d420/run | 8,000.0/s at 8,000/s (reached), p50 8.26 ms, p99 126.5 ms, failed 0 | – | – | commit d9b0781205, dirty False, started 2026-11-12T07:29:05.891689Z | none | none | – | 8.56 10.22 7.93 | 13.72 11.59 8.87 |
| r2-tag/run | 7,998.3/s at 8,000/s (**not reached**), p50 9.92 ms, p99 198.7 ms, failed 200 | – | – | commit d9b0781205, dirty False, started 2026-11-12T07:36:48.733938Z | none | none | – | 4.82 9.55 8.93 | 9.87 10.33 9.38 |

The allowed JVMs (environment.txt) over 6 measurements, 78 samples: mean 1.2%, max 82.5% of one core; a step is void with two samples in a row at 5% or more, or a build request in a daemon's log (allowed-jvms.txt).

Other JVMs seen during the measurements: none.

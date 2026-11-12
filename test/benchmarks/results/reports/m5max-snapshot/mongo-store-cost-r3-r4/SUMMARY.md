# mongo-store-cost-r3-r4: measurements

| Measurement | Peak (failed 1% or less) or result | Sustainable | Failed more than 1% | Last step or provenance | Disturbed steps | Void steps | Generator-bound steps | Load before | Load after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-before/run | 7,999.8/s at 8,000/s (**not reached**), p50 10.05 ms, p99 190.5 ms, failed 20 | – | – | commit d9b0781205, dirty False, started 2026-11-12T09:03:00.655191Z | none | none | – | 5.34 15.51 19.67 | 13.05 13.96 18.24 |
| r1-d420/run | 7,998.1/s at 8,000/s (**not reached**), p50 8.89 ms, p99 193.5 ms, failed 231 | – | – | commit d9b0781205, dirty False, started 2026-11-12T09:06:51.401539Z | none | none | – | 8.13 12.43 17.33 | 8.32 11.66 16.23 |
| r1-tag/run | 7,998.0/s at 8,000/s (**not reached**), p50 7.81 ms, p99 132.1 ms, failed 244 | – | – | commit d9b0781205, dirty False, started 2026-11-12T08:59:12.422157Z | none | none | – | 11.85 24.47 23.19 | 8.57 18.48 20.99 |
| r2-before/run | 7,993.1/s at 8,000/s (**not reached**), p50 7.29 ms, p99 124.9 ms, failed 832 | – | – | commit d9b0781205, dirty False, started 2026-11-12T09:14:31.180395Z | none | none | – | 7.69 10.10 14.18 | 8.27 10.09 13.52 |
| r2-d420/run | 8,000.0/s at 8,000/s (reached), p50 9.60 ms, p99 182.3 ms, failed 0 | – | – | commit d9b0781205, dirty False, started 2026-11-12T09:10:41.445563Z | none | none | – | 6.79 10.58 15.47 | 9.28 10.77 14.74 |
| r2-tag/run | 7,984.4/s at 8,000/s (**not reached**), p50 8.45 ms, p99 236.5 ms, failed 1872 | – | – | commit d9b0781205, dirty False, started 2026-11-12T09:18:19.289110Z | none | none | – | 9.03 10.19 13.30 | 8.64 10.10 12.78 |

The allowed JVMs (environment.txt) over 6 measurements, 78 samples: mean 0.0%, max 0.3% of one core; a step is void with two samples in a row at 5% or more, or a build request in a daemon's log (allowed-jvms.txt).

Other JVMs seen during the measurements: none.

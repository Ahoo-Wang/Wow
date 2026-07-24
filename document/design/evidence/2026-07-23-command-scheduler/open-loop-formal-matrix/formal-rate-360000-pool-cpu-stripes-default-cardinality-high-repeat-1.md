# Command PROCESSED Bounded Open-Loop Result

- Status: `SUCCESS`
- Run ID: `716b527f-710b-49e8-88f7-2b6f4cbb929e`
- Offered rate: `360000 commands/s`
- Scheduler: `pool=cpu(14), stripes=default(896)`
- Protocol: `constant absolute arrivals`, `LAST/PROCESSED`, `clientMaxInFlight=65536`, `server terminal drain`

| Metric | Value |
|---|---:|
| Measurement planned arrivals | 7200000 |
| Admitted | 7199936 |
| Generator missed | 64 |
| Generator expired | 0 |
| Shed at maxInFlight | 0 |
| Processed by deadline | 7199936 |
| Timed out | 0 |
| Server outstanding before scenario close | 0 |
| Processed yield | 100.0% |
| Measurement-window processed rate | 359997.9 commands/s |

> Percentiles in JSON are conditional on successful measurement-cohort completions. An all-offered p99 is reported only when yield is above 99%, using rank 99/yield.

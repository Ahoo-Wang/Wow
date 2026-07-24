# Command PROCESSED Bounded Open-Loop Result

- Status: `SUCCESS`
- Run ID: `716b527f-710b-49e8-88f7-2b6f4cbb929e`
- Offered rate: `400000 commands/s`
- Scheduler: `pool=cpu(14), stripes=default(896)`
- Protocol: `constant absolute arrivals`, `LAST/PROCESSED`, `clientMaxInFlight=65536`, `server terminal drain`

| Metric | Value |
|---|---:|
| Measurement planned arrivals | 8000000 |
| Admitted | 7327141 |
| Generator missed | 137 |
| Generator expired | 0 |
| Shed at maxInFlight | 672722 |
| Processed by deadline | 7327141 |
| Timed out | 0 |
| Server outstanding before scenario close | 0 |
| Processed yield | 91.59% |
| Measurement-window processed rate | 366368.15 commands/s |

> Percentiles in JSON are conditional on successful measurement-cohort completions. An all-offered p99 is reported only when yield is above 99%, using rank 99/yield.

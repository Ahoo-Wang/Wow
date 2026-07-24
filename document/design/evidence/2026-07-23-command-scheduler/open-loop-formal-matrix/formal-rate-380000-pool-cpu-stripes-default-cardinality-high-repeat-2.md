# Command PROCESSED Bounded Open-Loop Result

- Status: `SUCCESS`
- Run ID: `716b527f-710b-49e8-88f7-2b6f4cbb929e`
- Offered rate: `380000 commands/s`
- Scheduler: `pool=cpu(14), stripes=default(896)`
- Protocol: `constant absolute arrivals`, `LAST/PROCESSED`, `clientMaxInFlight=65536`, `server terminal drain`

| Metric | Value |
|---|---:|
| Measurement planned arrivals | 7600000 |
| Admitted | 7484131 |
| Generator missed | 80 |
| Generator expired | 0 |
| Shed at maxInFlight | 115789 |
| Processed by deadline | 7484131 |
| Timed out | 0 |
| Server outstanding before scenario close | 0 |
| Processed yield | 98.48% |
| Measurement-window processed rate | 374929.2 commands/s |

> Percentiles in JSON are conditional on successful measurement-cohort completions. An all-offered p99 is reported only when yield is above 99%, using rank 99/yield.

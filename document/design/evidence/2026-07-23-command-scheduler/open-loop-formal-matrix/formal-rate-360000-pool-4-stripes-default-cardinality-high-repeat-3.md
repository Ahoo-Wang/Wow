# Command PROCESSED Bounded Open-Loop Result

- Status: `SUCCESS`
- Run ID: `5476a813-ba3c-49f4-bf71-0d6011e24291`
- Offered rate: `360000 commands/s`
- Scheduler: `pool=4(4), stripes=default(896)`
- Protocol: `constant absolute arrivals`, `LAST/PROCESSED`, `clientMaxInFlight=65536`, `server terminal drain`

| Metric | Value |
|---|---:|
| Measurement planned arrivals | 7200000 |
| Admitted | 7158860 |
| Generator missed | 6 |
| Generator expired | 0 |
| Shed at maxInFlight | 41134 |
| Processed by deadline | 7158860 |
| Timed out | 0 |
| Server outstanding before scenario close | 0 |
| Processed yield | 99.43% |
| Measurement-window processed rate | 358334.85 commands/s |

> Percentiles in JSON are conditional on successful measurement-cohort completions. An all-offered p99 is reported only when yield is above 99%, using rank 99/yield.

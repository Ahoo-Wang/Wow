# Command PROCESSED Bounded Open-Loop Result

- Status: `SUCCESS`
- Run ID: `5476a813-ba3c-49f4-bf71-0d6011e24291`
- Offered rate: `380000 commands/s`
- Scheduler: `pool=4(4), stripes=default(896)`
- Protocol: `constant absolute arrivals`, `LAST/PROCESSED`, `clientMaxInFlight=65536`, `server terminal drain`

| Metric | Value |
|---|---:|
| Measurement planned arrivals | 7600000 |
| Admitted | 6053779 |
| Generator missed | 9 |
| Generator expired | 0 |
| Shed at maxInFlight | 1546212 |
| Processed by deadline | 6053779 |
| Timed out | 0 |
| Server outstanding before scenario close | 0 |
| Processed yield | 79.65% |
| Measurement-window processed rate | 302685.35 commands/s |

> Percentiles in JSON are conditional on successful measurement-cohort completions. An all-offered p99 is reported only when yield is above 99%, using rank 99/yield.

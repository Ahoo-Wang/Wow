# V10 on MongoDB: store operations per command (B.D diagnostics, low load)

**B.D diagnostics (decision D224), measured at low load. Not a benchmark:** it counts store operations per `Deposit`, not latency or throughput, at 20 operations/s so the fanless host does not throttle. Work in progress; the attribution, the fixes and the re-measurement follow in this pull request.

## Baseline (before)

Raw results: [`v10-mongo-write-amplification/before/`](v10-mongo-write-amplification/before/); tools: [`v10-mongo-write-amplification/tools/`](v10-mongo-write-amplification/tools/).

| Setup | Profiled operations per `Deposit` (idle rate removed) |
| --- | --- |
| 9.x, MongoDB | 4.00 |
| V10, MongoDB | 17.64 |
| V10, PostgreSQL (statements) | 14.38 |

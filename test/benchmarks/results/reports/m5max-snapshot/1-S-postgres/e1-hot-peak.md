Peak 9,431.6/s at 256 clients: the step at 4,096 clients failed 48.0% (527,870 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak.
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 9,431.6/s at 256 clients, p99 149.5 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-hot-sweep): 19,455.5/s answered, failed 47.7% (532,704 × 429 wow.command.busy); 4,096 clients (e1-hot-top): 19,078.7/s answered, failed 48.0% (527,870 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-hot-sweep | 1 | 728.1 | 4.0 | 0.00% |  |
| e1-hot-sweep | 4 | 1,992.8 | 5.3 | 0.00% |  |
| e1-hot-sweep | 16 | 2,197.3 | 23.6 | 0.00% |  |
| e1-hot-sweep | 64 | 6,514.0 | 49.4 | 0.00% |  |
| e1-hot-sweep | 256 | 9,431.6 | 149.5 | 0.00% |  |
| e1-hot-sweep | 1,024 | 8,446.8 | 405.5 | 0.00% |  |
| e1-hot-sweep | 4,096 | 19,455.5 | 1540.1 | 47.72% |  |
| e1-hot-top | 4,096 | 19,078.7 | 1572.9 | 47.98% |  |

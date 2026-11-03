Peak 1,634.7/s at 1,024 clients: the step at 4,096 clients failed 57.7% (656,829 × 429 wow.command.busy, 1,890 × 504 wow.wait.timeout), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (1,024 clients passed the 1,558.3/s below them by more than the band).
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 1,456.7/s at 64 clients, p99 165.9 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-hot-sweep): 18,301.3/s answered, failed 49.7% (543,008 × 429 wow.command.busy); 4,096 clients (e1-hot-top): 16,087.4/s answered, failed 57.7% (656,829 × 429 wow.command.busy, 1,890 × 504 wow.wait.timeout).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-hot-sweep | 1 | 326.3 | 6.7 | 0.00% |  |
| e1-hot-sweep | 4 | 774.1 | 14.4 | 0.00% |  |
| e1-hot-sweep | 16 | 1,088.4 | 48.9 | 0.00% |  |
| e1-hot-sweep | 64 | 1,456.7 | 165.9 | 0.00% |  |
| e1-hot-sweep | 256 | 1,558.3 | 581.6 | 0.00% |  |
| e1-hot-sweep | 1,024 | 1,634.7 | 1458.2 | 0.00% |  |
| e1-hot-sweep | 4,096 | 18,301.3 | 399.4 | 49.72% |  |
| e1-hot-top | 4,096 | 16,087.4 | 452.6 | 57.71% |  |

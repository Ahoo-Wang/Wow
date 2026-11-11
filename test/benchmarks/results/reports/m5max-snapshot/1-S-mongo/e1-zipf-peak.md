Peak 14,536.9/s at 1,024 clients: the step at 4,096 clients failed 13.1% (73,997 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (1,024 clients passed the 12,643.0/s below them by more than the band).
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 12,643.0/s at 256 clients, p99 68.6 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-zipf-sweep): 16,731.3/s answered, failed 13.5% (78,298 × 429 wow.command.busy); 4,096 clients (e1-zipf-top): 16,332.9/s answered, failed 13.1% (73,997 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-zipf-sweep | 1 | 324.1 | 6.8 | 0.00% |  |
| e1-zipf-sweep | 4 | 973.0 | 12.0 | 0.00% |  |
| e1-zipf-sweep | 16 | 2,057.4 | 22.8 | 0.00% |  |
| e1-zipf-sweep | 64 | 7,096.1 | 23.8 | 0.00% |  |
| e1-zipf-sweep | 256 | 12,643.0 | 68.6 | 0.00% |  |
| e1-zipf-sweep | 1,024 | 14,536.9 | 454.7 | 0.00% |  |
| e1-zipf-sweep | 4,096 | 16,731.3 | 2072.6 | 13.49% |  |
| e1-zipf-top | 4,096 | 16,332.9 | 2089.0 | 13.12% |  |

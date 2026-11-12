Peak 6,911.2/s at 1,024 clients: the step at 4,096 clients failed 49.5% (527,698 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (1,024 clients passed the 6,687.8/s below them by more than the band).
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 5,417.2/s at 64 clients, p99 150.5 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-hot-sweep): 18,645.7/s answered, failed 49.5% (548,113 × 429 wow.command.busy); 4,096 clients (e1-hot-top): 17,971.2/s answered, failed 49.5% (527,698 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-hot-sweep | 1 | 344.2 | 5.7 | 0.00% |  |
| e1-hot-sweep | 4 | 829.3 | 12.5 | 0.00% |  |
| e1-hot-sweep | 16 | 2,203.6 | 20.9 | 0.00% |  |
| e1-hot-sweep | 64 | 5,417.2 | 150.5 | 0.00% |  |
| e1-hot-sweep | 256 | 6,687.8 | 248.8 | 0.00% |  |
| e1-hot-sweep | 1,024 | 6,911.2 | 471.0 | 0.00% |  |
| e1-hot-sweep | 4,096 | 18,645.7 | 4653.1 | 49.49% |  |
| e1-hot-top | 4,096 | 17,971.2 | 4751.4 | 49.46% |  |

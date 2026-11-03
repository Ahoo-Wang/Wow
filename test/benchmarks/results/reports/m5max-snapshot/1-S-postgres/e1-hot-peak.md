Peak 12,351.4/s at 1,024 clients: the step at 4,096 clients failed 49.0% (696,339 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (1,024 clients passed the 8,851.6/s below them by more than the band).
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 8,000.2/s at 256 clients, p99 155.6 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-hot-sweep): 23,768.6/s answered, failed 48.9% (681,803 × 429 wow.command.busy); 4,096 clients (e1-hot-top): 24,142.2/s answered, failed 49.0% (696,339 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-hot-sweep | 1 | 947.5 | 2.7 | 0.00% |  |
| e1-hot-sweep | 4 | 2,099.0 | 4.4 | 0.00% |  |
| e1-hot-sweep | 16 | 4,614.9 | 7.7 | 0.00% |  |
| e1-hot-sweep | 64 | 8,851.6 | 18.8 | 0.00% |  |
| e1-hot-sweep | 256 | 8,000.2 | 155.6 | 0.00% |  |
| e1-hot-sweep | 1,024 | 12,351.4 | 331.8 | 0.00% |  |
| e1-hot-sweep | 4,096 | 23,768.6 | 1876.0 | 48.88% |  |
| e1-hot-top | 4,096 | 24,142.2 | 2195.5 | 49.02% |  |

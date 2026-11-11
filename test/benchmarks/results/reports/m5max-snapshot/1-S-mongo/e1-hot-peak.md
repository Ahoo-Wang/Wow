Peak 8,984.5/s at 1,024 clients: the step at 4,096 clients failed 49.5% (530,249 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (1,024 clients passed the 8,079.4/s below them by more than the band).
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 8,079.4/s at 256 clients, p99 147.5 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-hot-sweep): 18,920.3/s answered, failed 49.4% (554,978 × 429 wow.command.busy); 4,096 clients (e1-hot-top): 18,025.7/s answered, failed 49.5% (530,249 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-hot-sweep | 1 | 330.0 | 6.7 | 0.00% |  |
| e1-hot-sweep | 4 | 1,047.5 | 10.4 | 0.00% |  |
| e1-hot-sweep | 16 | 3,236.5 | 12.4 | 0.00% |  |
| e1-hot-sweep | 64 | 7,306.6 | 22.0 | 0.00% |  |
| e1-hot-sweep | 256 | 8,079.4 | 147.5 | 0.00% |  |
| e1-hot-sweep | 1,024 | 8,984.5 | 346.1 | 0.00% |  |
| e1-hot-sweep | 4,096 | 18,920.3 | 4358.1 | 49.44% |  |
| e1-hot-top | 4,096 | 18,025.7 | 4849.7 | 49.51% |  |

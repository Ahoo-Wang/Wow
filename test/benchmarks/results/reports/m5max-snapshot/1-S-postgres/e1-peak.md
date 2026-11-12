Peak 37,713.6/s at 4,096 clients: the step at 8,192 clients failed 4.0% (26,600 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (4,096 clients passed the 23,030.1/s below them by more than the band).
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 23,030.1/s at 1,024 clients, p99 74.8 ms.
Failed more than 1% (not a peak): 8,192 clients (e1-top): 21,273.0/s answered, failed 4.0% (26,600 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-sweep | 1 | 776.3 | 3.6 | 0.00% |  |
| e1-sweep | 4 | 684.4 | 16.1 | 0.00% |  |
| e1-sweep | 16 | 2,643.5 | 15.3 | 0.00% |  |
| e1-sweep | 64 | 9,676.4 | 15.9 | 0.00% |  |
| e1-sweep | 256 | 16,241.7 | 28.5 | 0.00% |  |
| e1-sweep | 1,024 | 23,030.1 | 74.8 | 0.00% |  |
| e1-sweep | 4,096 | 37,713.6 | 212.0 | 0.00% |  |
| e1-top | 4,096 | 36,719.5 | 227.3 | 0.00% |  |
| e1-top | 8,192 | 21,273.0 | 557.1 | 4.00% |  |

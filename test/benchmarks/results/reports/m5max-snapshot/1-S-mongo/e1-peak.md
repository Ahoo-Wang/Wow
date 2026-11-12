Peak 21,822.6/s at 4,096 clients: the step at 8,192 clients failed 16.4% (104,730 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (4,096 clients passed the 19,759.6/s below them by more than the band).
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 19,759.6/s at 1,024 clients, p99 102.4 ms.
Failed more than 1% (not a peak): 8,192 clients (e1-top): 17,754.7/s answered, failed 16.4% (104,730 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-sweep | 1 | 292.7 | 7.5 | 0.00% |  |
| e1-sweep | 4 | 1,020.6 | 11.9 | 0.00% |  |
| e1-sweep | 16 | 1,885.9 | 29.2 | 0.00% |  |
| e1-sweep | 64 | 5,093.1 | 153.6 | 0.00% |  |
| e1-sweep | 256 | 11,021.2 | 189.4 | 0.00% |  |
| e1-sweep | 1,024 | 19,759.6 | 102.4 | 0.00% |  |
| e1-sweep | 4,096 | 21,775.2 | 679.9 | 0.00% |  |
| e1-top | 4,096 | 21,822.6 | 503.8 | 0.00% |  |
| e1-top | 8,192 | 17,754.7 | 913.4 | 16.43% |  |

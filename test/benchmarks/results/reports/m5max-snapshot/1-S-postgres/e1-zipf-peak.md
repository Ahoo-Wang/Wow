Peak 16,139.1/s at 1,024 clients: the step at 4,096 clients failed 13.0% (85,597 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (1,024 clients passed the 15,417.2/s below them by more than the band).
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 15,417.2/s at 256 clients, p99 45.3 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-zipf-sweep): 19,902.2/s answered, failed 12.8% (87,288 × 429 wow.command.busy); 4,096 clients (e1-zipf-top): 19,070.2/s answered, failed 13.0% (85,597 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-zipf-sweep | 1 | 1,153.6 | 1.9 | 0.00% |  |
| e1-zipf-sweep | 4 | 2,081.1 | 4.8 | 0.00% |  |
| e1-zipf-sweep | 16 | 2,858.9 | 16.9 | 0.00% |  |
| e1-zipf-sweep | 64 | 10,393.1 | 15.6 | 0.00% |  |
| e1-zipf-sweep | 256 | 15,417.2 | 45.3 | 0.00% |  |
| e1-zipf-sweep | 1,024 | 16,139.1 | 423.9 | 0.00% |  |
| e1-zipf-sweep | 4,096 | 19,902.2 | 1941.5 | 12.75% |  |
| e1-zipf-top | 4,096 | 19,070.2 | 1933.3 | 13.01% |  |

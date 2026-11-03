Peak 22,886.7/s at 4,096 clients: the step at 8,192 clients failed 8.3% (56,003 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (4,096 clients passed the 19,580.4/s below them by more than the band).
Noise band 4.1% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 19,580.4/s at 1,024 clients, p99 116.7 ms.
Failed more than 1% (not a peak): 8,192 clients (e1-top): 20,601.2/s answered, failed 8.3% (56,003 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-sweep | 1 | 327.7 | 6.4 | 0.00% |  |
| e1-sweep | 4 | 1,285.0 | 8.1 | 0.00% |  |
| e1-sweep | 16 | 3,861.0 | 11.1 | 0.00% |  |
| e1-sweep | 64 | 7,488.0 | 21.8 | 0.00% |  |
| e1-sweep | 256 | 13,935.7 | 39.9 | 0.00% |  |
| e1-sweep | 1,024 | 19,580.4 | 116.7 | 0.00% |  |
| e1-sweep | 4,096 | 22,886.7 | 354.3 | 0.00% |  |
| e1-top | 4,096 | 21,949.7 | 376.8 | 0.00% |  |
| e1-top | 8,192 | 20,601.2 | 589.8 | 8.31% |  |

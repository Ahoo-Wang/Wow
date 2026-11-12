Peak 11,521.2/s at 1,024 clients: the step at 4,096 clients failed 48.1% (606,290 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (1,024 clients passed the 8,520.7/s below them by more than the band).
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 8,520.7/s at 256 clients, p99 155.6 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-hot-sweep): 21,779.9/s answered, failed 48.1% (605,525 × 429 wow.command.busy); 4,096 clients (e1-hot-top): 21,789.3/s answered, failed 48.1% (606,290 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-hot-sweep | 1 | 639.1 | 4.0 | 0.00% |  |
| e1-hot-sweep | 4 | 729.5 | 15.8 | 0.00% |  |
| e1-hot-sweep | 16 | 2,955.3 | 16.2 | 0.00% |  |
| e1-hot-sweep | 64 | 7,664.9 | 22.3 | 0.00% |  |
| e1-hot-sweep | 256 | 8,520.7 | 155.6 | 0.00% |  |
| e1-hot-sweep | 1,024 | 11,521.2 | 303.1 | 0.00% |  |
| e1-hot-sweep | 4,096 | 21,779.9 | 1548.3 | 48.10% |  |
| e1-hot-top | 4,096 | 21,789.3 | 1490.9 | 48.12% |  |

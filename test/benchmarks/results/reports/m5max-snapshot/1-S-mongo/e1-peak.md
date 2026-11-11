Peak 21,458.6/s at 4,096 clients: the step at 8,192 clients failed 4.3% (23,949 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (4,096 clients passed the 17,946.6/s below them by more than the band).
Noise band 3.6% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 17,946.6/s at 1,024 clients, p99 135.2 ms.
Failed more than 1% (not a peak): 8,192 clients (e1-top): 17,632.7/s answered, failed 4.3% (23,949 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-sweep | 1 | 324.9 | 6.8 | 0.00% |  |
| e1-sweep | 4 | 1,051.4 | 10.0 | 0.00% |  |
| e1-sweep | 16 | 3,643.8 | 11.3 | 0.00% |  |
| e1-sweep | 64 | 5,863.7 | 52.2 | 0.00% |  |
| e1-sweep | 256 | 12,751.6 | 47.4 | 0.00% |  |
| e1-sweep | 1,024 | 17,946.6 | 135.2 | 0.00% |  |
| e1-sweep | 4,096 | 21,458.6 | 366.6 | 0.00% |  |
| e1-top | 4,096 | 20,694.2 | 434.2 | 0.00% |  |
| e1-top | 8,192 | 17,632.7 | 655.4 | 4.33% |  |

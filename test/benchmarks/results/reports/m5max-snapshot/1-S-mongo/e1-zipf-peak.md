Peak 15,957.5/s at 1,024 clients: the step at 4,096 clients failed 14.4% (98,291 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (1,024 clients passed the 14,914.4/s below them by more than the band).
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 14,914.4/s at 256 clients, p99 42.2 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-zipf-sweep): 20,449.9/s answered, failed 14.4% (102,934 × 429 wow.command.busy); 4,096 clients (e1-zipf-top): 19,552.6/s answered, failed 14.4% (98,291 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-zipf-sweep | 1 | 493.9 | 5.4 | 0.00% |  |
| e1-zipf-sweep | 4 | 1,546.8 | 6.8 | 0.00% |  |
| e1-zipf-sweep | 16 | 3,753.1 | 11.1 | 0.00% |  |
| e1-zipf-sweep | 64 | 7,776.6 | 19.8 | 0.00% |  |
| e1-zipf-sweep | 256 | 14,914.4 | 42.2 | 0.00% |  |
| e1-zipf-sweep | 1,024 | 15,957.5 | 397.3 | 0.00% |  |
| e1-zipf-sweep | 4,096 | 20,449.9 | 1507.3 | 14.37% |  |
| e1-zipf-top | 4,096 | 19,552.6 | 1605.6 | 14.35% |  |

Peak 13,372.5/s at 1,024 clients: the step at 4,096 clients failed 13.1% (78,858 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (1,024 clients passed the 8,015.4/s below them by more than the band).
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 3,353.8/s at 64 clients, p99 197.6 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-zipf-sweep): 15,452.7/s answered, failed 13.7% (73,748 × 429 wow.command.busy); 4,096 clients (e1-zipf-top): 17,511.2/s answered, failed 13.1% (78,858 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-zipf-sweep | 1 | 292.0 | 7.4 | 0.00% |  |
| e1-zipf-sweep | 4 | 998.9 | 14.0 | 0.00% |  |
| e1-zipf-sweep | 16 | 1,640.8 | 96.3 | 0.00% |  |
| e1-zipf-sweep | 64 | 3,353.8 | 197.6 | 0.00% |  |
| e1-zipf-sweep | 256 | 8,015.4 | 239.6 | 0.00% |  |
| e1-zipf-sweep | 1,024 | 13,372.5 | 585.7 | 0.00% |  |
| e1-zipf-sweep | 4,096 | 15,452.7 | 2490.4 | 13.72% |  |
| e1-zipf-top | 4,096 | 17,511.2 | 1859.6 | 13.05% |  |

Peak 19,746.8/s at 256 clients: the step at 4,096 clients failed 14.3% (128,487 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak.
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 19,746.8/s at 256 clients, p99 29.6 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-zipf-sweep): 25,193.8/s answered, failed 14.4% (126,957 × 429 wow.command.busy); 4,096 clients (e1-zipf-top): 25,574.1/s answered, failed 14.3% (128,487 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-zipf-sweep | 1 | 791.3 | 3.5 | 0.00% |  |
| e1-zipf-sweep | 4 | 1,650.6 | 7.5 | 0.00% |  |
| e1-zipf-sweep | 16 | 4,712.8 | 10.5 | 0.00% |  |
| e1-zipf-sweep | 64 | 9,838.0 | 17.8 | 0.00% |  |
| e1-zipf-sweep | 256 | 19,746.8 | 29.6 | 0.00% |  |
| e1-zipf-sweep | 1,024 | 19,335.5 | 337.9 | 0.00% |  |
| e1-zipf-sweep | 4,096 | 25,193.8 | 1425.4 | 14.38% |  |
| e1-zipf-top | 4,096 | 25,574.1 | 1409.0 | 14.34% |  |

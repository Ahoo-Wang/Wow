Peak 11,853.7/s at 256 clients: the step at 4,096 clients failed 45.3% (518,661 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak.
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 11,853.7/s at 256 clients, p99 76.8 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-hot-sweep): 21,540.2/s answered, failed 45.3% (535,534 × 429 wow.command.busy); 4,096 clients (e1-hot-top): 20,876.1/s answered, failed 45.3% (518,661 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-hot-sweep | 1 | 1,027.3 | 3.1 | 0.00% |  |
| e1-hot-sweep | 4 | 2,008.8 | 6.9 | 0.00% |  |
| e1-hot-sweep | 16 | 4,678.7 | 10.3 | 0.00% |  |
| e1-hot-sweep | 64 | 10,806.2 | 13.8 | 0.00% |  |
| e1-hot-sweep | 256 | 11,853.7 | 76.8 | 0.00% |  |
| e1-hot-sweep | 1,024 | 11,761.3 | 297.0 | 0.00% |  |
| e1-hot-sweep | 4,096 | 21,540.2 | 651.3 | 45.32% |  |
| e1-hot-top | 4,096 | 20,876.1 | 696.3 | 45.30% |  |

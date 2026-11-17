Peak 9,948.1/s at 256 clients: the step at 4,096 clients failed 49.2% (671,697 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak.
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 9,948.1/s at 256 clients, p99 78.8 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-hot-sweep): 20,048.4/s answered, failed 47.9% (552,724 × 429 wow.command.busy); 4,096 clients (e1-hot-top): 23,132.8/s answered, failed 49.2% (671,697 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-hot-sweep | 1 | 369.4 | 6.0 | 0.00% |  |
| e1-hot-sweep | 4 | 1,391.9 | 7.5 | 0.00% |  |
| e1-hot-sweep | 16 | 3,740.6 | 10.8 | 0.00% |  |
| e1-hot-sweep | 64 | 8,013.7 | 18.7 | 0.00% |  |
| e1-hot-sweep | 256 | 9,948.1 | 78.8 | 0.00% |  |
| e1-hot-sweep | 1,024 | 9,588.9 | 309.2 | 0.00% |  |
| e1-hot-sweep | 4,096 | 20,048.4 | 1392.6 | 47.89% |  |
| e1-hot-top | 4,096 | 23,132.8 | 2113.5 | 49.18% |  |

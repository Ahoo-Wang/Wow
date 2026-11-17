Peak 41,706.1/s at 4,096 clients: the step at 8,192 clients failed 2.7% (19,145 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak.
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 41,706.1/s at 4,096 clients, p99 167.9 ms.
Failed more than 1% (not a peak): 8,192 clients (e1-top): 22,659.7/s answered, failed 2.7% (19,145 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-sweep | 1 | 1,298.7 | 1.2 | 0.00% |  |
| e1-sweep | 4 | 2,353.2 | 5.5 | 0.00% |  |
| e1-sweep | 16 | 4,174.5 | 10.6 | 0.00% |  |
| e1-sweep | 64 | 14,623.2 | 8.8 | 0.00% |  |
| e1-sweep | 256 | 23,754.2 | 21.0 | 0.00% |  |
| e1-sweep | 1,024 | 41,605.7 | 46.1 | 0.00% |  |
| e1-sweep | 4,096 | 41,706.1 | 167.9 | 0.00% |  |
| e1-top | 4,096 | 40,594.1 | 183.3 | 0.00% |  |
| e1-top | 8,192 | 22,659.7 | 454.7 | 2.74% |  |

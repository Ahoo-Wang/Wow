Peak 3,898.2/s at 256 clients: the step at 4,096 clients failed 13.3% (32,028 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak.
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 2,819.9/s at 64 clients, p99 123.4 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-zipf-sweep): 6,667.6/s answered, failed 13.3% (30,749 × 429 wow.command.busy); 4,096 clients (e1-zipf-top): 6,982.9/s answered, failed 13.3% (32,028 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-zipf-sweep | 1 | 325.8 | 6.5 | 0.00% |  |
| e1-zipf-sweep | 4 | 1,038.1 | 10.1 | 0.00% |  |
| e1-zipf-sweep | 16 | 1,758.2 | 36.6 | 0.00% |  |
| e1-zipf-sweep | 64 | 2,819.9 | 123.4 | 0.00% |  |
| e1-zipf-sweep | 256 | 3,898.2 | 297.0 | 0.00% |  |
| e1-zipf-sweep | 1,024 | 2,672.6 | 3096.6 | 0.00% |  |
| e1-zipf-sweep | 4,096 | 6,667.6 | 5406.7 | 13.32% |  |
| e1-zipf-top | 4,096 | 6,982.9 | 4915.2 | 13.26% |  |

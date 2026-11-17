Peak 28,609.4/s at 4,096 clients: the step at 8,192 clients failed 2.0% (12,468 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak.
Noise band 7.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 28,196.1/s at 1,024 clients, p99 66.6 ms.
Failed more than 1% (not a peak): 8,192 clients (e1-top): 20,037.3/s answered, failed 2.0% (12,468 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-sweep | 1 | 434.6 | 5.6 | 0.00% |  |
| e1-sweep | 4 | 1,575.3 | 6.8 | 0.00% |  |
| e1-sweep | 16 | 3,059.3 | 13.6 | 0.00% |  |
| e1-sweep | 64 | 8,699.1 | 16.6 | 0.00% |  |
| e1-sweep | 256 | 16,583.7 | 28.5 | 0.00% |  |
| e1-sweep | 1,024 | 28,196.1 | 66.6 | 0.00% |  |
| e1-sweep | 4,096 | 28,609.4 | 325.6 | 0.00% |  |
| e1-top | 4,096 | 26,615.9 | 333.8 | 0.00% |  |
| e1-top | 8,192 | 20,037.3 | 475.1 | 2.03% |  |

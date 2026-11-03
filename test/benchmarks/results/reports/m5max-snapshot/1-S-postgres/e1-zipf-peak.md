Peak 15,112.4/s at 1,024 clients: the step at 4,096 clients failed 13.5% (82,969 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak.
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 15,063.1/s at 256 clients, p99 45.1 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-zipf-sweep): 19,754.8/s answered, failed 13.8% (95,019 × 429 wow.command.busy); 4,096 clients (e1-zipf-top): 17,714.5/s answered, failed 13.5% (82,969 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-zipf-sweep | 1 | 1,066.1 | 1.9 | 0.00% |  |
| e1-zipf-sweep | 4 | 2,437.9 | 3.1 | 0.00% |  |
| e1-zipf-sweep | 16 | 5,328.7 | 6.5 | 0.00% |  |
| e1-zipf-sweep | 64 | 9,290.6 | 18.9 | 0.00% |  |
| e1-zipf-sweep | 256 | 15,063.1 | 45.1 | 0.00% |  |
| e1-zipf-sweep | 1,024 | 15,112.4 | 565.2 | 0.00% |  |
| e1-zipf-sweep | 4,096 | 19,754.8 | 1810.4 | 13.82% |  |
| e1-zipf-top | 4,096 | 17,714.5 | 1835.0 | 13.50% |  |

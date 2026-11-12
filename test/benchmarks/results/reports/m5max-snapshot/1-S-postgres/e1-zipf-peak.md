Peak 17,090.6/s at 1,024 clients: the step at 4,096 clients failed 10.7% (74,031 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (1,024 clients passed the 15,386.1/s below them by more than the band).
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 15,386.1/s at 256 clients, p99 52.2 ms.
Failed more than 1% (not a peak): 4,096 clients (e1-zipf-sweep): 20,308.3/s answered, failed 12.0% (83,231 × 429 wow.command.busy); 4,096 clients (e1-zipf-top): 20,620.8/s answered, failed 10.7% (74,031 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-zipf-sweep | 1 | 524.3 | 4.2 | 0.00% |  |
| e1-zipf-sweep | 4 | 684.1 | 16.0 | 0.00% |  |
| e1-zipf-sweep | 16 | 2,466.7 | 18.0 | 0.00% |  |
| e1-zipf-sweep | 64 | 7,402.5 | 24.8 | 0.00% |  |
| e1-zipf-sweep | 256 | 15,386.1 | 52.2 | 0.00% |  |
| e1-zipf-sweep | 1,024 | 17,090.6 | 389.1 | 0.00% |  |
| e1-zipf-sweep | 4,096 | 20,308.3 | 1835.0 | 12.02% |  |
| e1-zipf-top | 4,096 | 20,620.8 | 1703.9 | 10.69% |  |

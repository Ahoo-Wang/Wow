Peak 32,303.0/s at 4,096 clients: the step at 8,192 clients failed 2.1% (13,373 × 429 wow.command.busy), which ends a sweep; a step that fails more than 1% is not a peak; the throughput was still rising when the failures began (4,096 clients passed the 24,541.9/s below them by more than the band).
Noise band 3.0% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 24,541.9/s at 1,024 clients, p99 95.2 ms.
Failed more than 1% (not a peak): 8,192 clients (e1-top): 20,823.6/s answered, failed 2.1% (13,373 × 429 wow.command.busy).

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-sweep | 1 | 1,058.5 | 1.7 | 0.00% |  |
| e1-sweep | 4 | 2,399.4 | 3.1 | 0.00% |  |
| e1-sweep | 16 | 5,543.3 | 5.9 | 0.00% |  |
| e1-sweep | 64 | 9,855.7 | 15.6 | 0.00% |  |
| e1-sweep | 256 | 16,411.8 | 26.0 | 0.00% |  |
| e1-sweep | 1,024 | 24,541.9 | 95.2 | 0.00% |  |
| e1-sweep | 4,096 | 31,484.9 | 297.0 | 0.00% |  |
| e1-top | 4,096 | 32,303.0 | 261.1 | 0.00% |  |
| e1-top | 8,192 | 20,823.6 | 495.6 | 2.10% |  |

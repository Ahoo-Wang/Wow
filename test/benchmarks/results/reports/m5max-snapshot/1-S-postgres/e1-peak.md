Peak 33,938.5/s at 4,096 clients: 8,192 clients answered 18,695.6/s, -44.9% on the best step below it, within the band or a fall.
Noise band 3.1% (the larger of 3% and the difference between two runs of one concurrency that failed 1% or less).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 21,373.1/s at 1,024 clients, p99 113.2 ms.
Failed more than 1% (not a peak): none.

| Measurement | Clients | Throughput (/s) | p99 (ms) | Failed | Generator-bound |
| --- | ---: | ---: | ---: | ---: | --- |
| e1-sweep | 1 | 973.9 | 3.8 | 0.00% |  |
| e1-sweep | 4 | 1,521.2 | 8.9 | 0.00% |  |
| e1-sweep | 16 | 5,227.5 | 8.5 | 0.00% |  |
| e1-sweep | 64 | 10,821.4 | 14.9 | 0.00% |  |
| e1-sweep | 256 | 15,139.2 | 35.1 | 0.00% |  |
| e1-sweep | 1,024 | 21,373.1 | 113.2 | 0.00% |  |
| e1-sweep | 4,096 | 33,938.5 | 229.4 | 0.00% |  |
| e1-top | 4,096 | 32,879.1 | 236.5 | 0.00% |  |
| e1-top | 8,192 | 18,695.6 | 569.3 | 0.53% |  |

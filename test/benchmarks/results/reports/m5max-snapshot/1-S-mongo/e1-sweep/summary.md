# 1-S-mongo/e1-sweep: E1 (uniform), 1 node(s) on mongo

Peak ≥ 22,886.7/s at 4,096 clients (its last step: a lower bound).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 19,580.4/s at 1,024 clients, p99 116.7 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 327.7 | 2.93 | 6.43 | 9,830 | 0 | 0.12 | 0.07 | 0.19 / 0.34 | 1.1 | 9.8 |  |
| 4 | 1,285.0 | 2.37 | 8.13 | 38,551 | 0 | 0.16 | 0.04 | 0.07 / 0.14 | 2.4 | 5.9 |  |
| 16 | 3,861.0 | 3.47 | 11.13 | 115,829 | 0 | 0.40 | 0.07 | 0.13 / 0.17 | 3.3 | 6.7 |  |
| 64 | 7,488.0 | 7.17 | 21.76 | 224,639 | 0 | 0.71 | 0.11 | 0.11 / 0.15 | 5.6 | 8.7 |  |
| 256 | 13,935.7 | 17.66 | 39.94 | 418,071 | 0 | 1.30 | 0.18 | 0.07 / 0.08 | 7.5 | 10.8 |  |
| 1,024 | 19,580.4 | 51.97 | 116.73 | 587,411 | 0 | 1.69 | 0.20 | 0.17 / 0.35 | 10.7 | 15.1 |  |
| 4,096 | 22,886.7 | 174.08 | 354.30 | 686,601 | 0 | 1.78 | 0.23 | 0.14 / 0.17 | 11.8 | 20.3 |  |

Load averages before: 11.52 10.88 9.94; after: 19.50 12.22 10.44.

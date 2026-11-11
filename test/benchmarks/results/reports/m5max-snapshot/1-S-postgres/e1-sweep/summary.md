# 1-S-postgres/e1-sweep: E1 (uniform), 1 node(s) on postgres

Peak ≥ 33,938.5/s at 4,096 clients (its last step: a lower bound).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 21,373.1/s at 1,024 clients, p99 113.2 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 973.9 | 0.80 | 3.79 | 29,216 | 0 | 0.12 | 0.03 | 0.92 / 1.11 | 2.5 | 7.8 |  |
| 4 | 1,521.2 | 1.80 | 8.89 | 45,635 | 0 | 0.18 | 0.06 | 0.87 / 0.90 | 3.2 | 7.2 |  |
| 16 | 5,227.5 | 2.65 | 8.51 | 156,824 | 0 | 0.60 | 0.12 | 1.25 / 1.79 | 7.1 | 9.1 |  |
| 64 | 10,821.4 | 5.12 | 14.91 | 324,642 | 0 | 1.11 | 0.16 | 0.96 / 1.02 | 7.8 | 12.6 |  |
| 256 | 15,139.2 | 16.32 | 35.07 | 454,177 | 0 | 1.43 | 0.19 | 1.06 / 1.32 | 8.5 | 12.3 |  |
| 1,024 | 21,373.1 | 46.59 | 113.15 | 641,194 | 0 | 1.77 | 0.29 | 0.97 / 1.17 | 10.1 | 13.9 |  |
| 4,096 | 33,938.5 | 117.76 | 229.38 | 1,018,156 | 0 | 2.72 | 0.40 | 1.03 / 1.09 | 14.3 | 24.4 |  |

Load averages before: 7.52 4.17 2.47; after: 23.57 11.69 6.05.

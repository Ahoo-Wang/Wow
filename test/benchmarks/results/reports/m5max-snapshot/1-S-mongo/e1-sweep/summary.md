# 1-S-mongo/e1-sweep: E1 (uniform), 1 node(s) on mongo

Peak ≥ 21,775.2/s at 4,096 clients (its last step: a lower bound).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 19,759.6/s at 1,024 clients, p99 102.4 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 292.7 | 2.98 | 7.46 | 8,782 | 0 | 0.15 | 0.03 | 0.47 / 0.71 | 1.5 | 12.3 |  |
| 4 | 1,020.6 | 2.67 | 11.90 | 30,618 | 0 | 0.17 | 0.05 | 0.46 / 0.62 | 2.7 | 8.7 |  |
| 16 | 1,885.9 | 6.53 | 29.18 | 56,578 | 0 | 0.23 | 0.07 | 0.41 / 0.60 | 3.6 | 9.1 |  |
| 64 | 5,093.1 | 9.41 | 153.60 | 152,794 | 0 | 0.41 | 0.06 | 0.31 / 0.41 | 3.3 | 12.2 |  |
| 256 | 11,021.2 | 18.69 | 189.44 | 330,637 | 0 | 0.97 | 0.14 | 0.27 / 0.37 | 6.1 | 12.7 |  |
| 1,024 | 19,759.6 | 51.97 | 102.40 | 592,788 | 0 | 1.54 | 0.22 | 0.34 / 0.47 | 10.0 | 18.5 |  |
| 4,096 | 21,775.2 | 171.01 | 679.93 | 653,256 | 0 | 1.55 | 0.22 | 0.32 / 0.38 | 12.6 | 21.6 |  |

Load averages before: 13.64 11.00 9.86; after: 21.59 13.96 11.18.

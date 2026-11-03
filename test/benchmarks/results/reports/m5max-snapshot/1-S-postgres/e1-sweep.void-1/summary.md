# 1-S-postgres/e1-sweep.void-1: E1 (uniform), 1 node(s) on postgres

Peak ≥ 34,878.5/s at 4,096 clients (its last step: a lower bound).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 26,854.6/s at 1,024 clients, p99 68.1 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 1,077.2 | 0.91 | 1.68 | 32,315 | 0 | 0.12 | 0.04 | 0.52 / 0.73 | 2.1 | 10.6 |  |
| 4 | 1,998.1 | 1.58 | 6.56 | 59,942 | 0 | 0.25 | 0.08 | 0.22 / 0.37 | 4.7 | 7.8 |  |
| 16 | 5,921.3 | 2.54 | 5.47 | 177,638 | 0 | 0.70 | 0.12 | 0.14 / 0.26 | 6.1 | 8.5 |  |
| 64 | 12,079.7 | 4.67 | 10.81 | 362,390 | 0 | 1.26 | 0.18 | 0.74 / 1.79 | 7.1 | 14.2 |  |
| 256 | 15,089.3 | 16.77 | 36.61 | 452,680 | 0 | 1.50 | 0.20 | 0.07 / 0.08 | 7.6 | 15.6 |  |
| 1,024 | 26,854.6 | 37.89 | 68.09 | 805,638 | 0 | 2.49 | 0.31 | 0.10 / 0.16 | 11.5 | 16.1 |  |
| 4,096 | 34,878.5 | 113.15 | 323.58 | 1,046,356 | 0 | 3.01 | 0.37 | 0.23 / 0.32 | 14.0 | 20.0 |  |

Load averages before: 12.10 4.77 2.58; after: 21.53 11.57 5.99.

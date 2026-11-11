# 3-N-postgres/e1-sweep: E1 (uniform), 3 node(s) on postgres

Peak 21,166.6/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 21,166.6/s at 1,024 clients, p99 124.9 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 532.4 | 1.49 | 3.57 | 15,972 | 0 | 0.15 | 0.03 | 0.07 / 0.14 | 1.7 | 14.8 |  |
| 4 | 2,089.0 | 1.62 | 5.63 | 62,670 | 0 | 0.26 | 0.09 | 0.04 / 0.06 | 4.6 | 9.9 |  |
| 16 | 2,939.2 | 4.35 | 13.50 | 88,176 | 0 | 0.45 | 0.13 | 0.04 / 0.06 | 8.2 | 12.3 |  |
| 64 | 7,805.9 | 6.66 | 16.51 | 234,178 | 0 | 0.93 | 0.16 | 0.06 / 0.10 | 12.4 | 12.2 |  |
| 256 | 16,187.6 | 15.36 | 28.16 | 485,628 | 0 | 1.52 | 0.22 | 0.08 / 0.12 | 14.0 | 26.1 |  |
| 1,024 | 21,166.6 | 35.58 | 124.93 | 634,999 | 0 | 1.84 | 0.25 | 0.07 / 0.15 | 15.2 | 33.8 |  |
| 4,096 | 10,759.0 | 94.21 | 2260.99 | 322,770 | 0 | 1.05 | 0.13 | 0.03 / 0.04 | 15.8 | 39.2 |  |

Load averages before: 18.81 14.26 12.45; after: 35.48 21.84 15.77.

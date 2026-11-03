# 1-S-postgres/e3-sweep: E3 (uniform), 1 node(s) on postgres

Peak 7,176.3/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 3,799.1/s at 64 clients, p99 31.0 ms.
Failed more than 1% (not a peak): 4,096 clients: 4,325.8/s answered, failed 75.6% (399,748 × 429 wow.command.busy, 1,719 × 504 wow.wait.timeout). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 477.6 | 1.93 | 4.51 | 14,327 | 0 | 0.14 | 0.02 | 0.47 / 0.58 | 3.4 | 15.8 |  |
| 4 | 888.8 | 3.85 | 13.05 | 26,664 | 0 | 0.14 | 0.04 | 0.36 / 0.41 | 3.6 | 10.9 |  |
| 16 | 1,859.6 | 6.94 | 23.68 | 55,789 | 0 | 0.27 | 0.06 | 0.49 / 0.63 | 4.4 | 8.4 |  |
| 64 | 3,799.1 | 15.55 | 30.98 | 113,974 | 0 | 0.47 | 0.07 | 0.47 / 0.61 | 6.3 | 8.2 |  |
| 256 | 4,474.8 | 51.20 | 532.48 | 134,244 | 0 | 0.47 | 0.07 | 0.27 / 0.41 | 6.3 | 10.8 |  |
| 1,024 | 7,176.3 | 102.91 | 811.01 | 215,289 | 0 | 0.66 | 0.08 | 0.08 / 0.14 | 5.9 | 14.6 |  |
| 4,096 | 4,325.8 | 415.74 | 5210.11 | 129,775 | 399,748 × http-429-wow.command.busy, 1,719 × http-504-wow.wait.timeout | 1.56 | 0.17 | 0.16 / 0.27 | 9.9 | 16.3 |  |

Load averages before: 19.29 11.35 9.88; after: 13.87 11.24 10.00.

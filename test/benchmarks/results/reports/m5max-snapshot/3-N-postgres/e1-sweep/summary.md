# 3-N-postgres/e1-sweep: E1 (uniform), 3 node(s) on postgres

Peak 19,274.8/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 19,274.8/s at 1,024 clients, p99 143.4 ms.
Failed more than 1% (not a peak): 4,096 clients: 9,837.1/s answered, failed 10.2% (24,075 × 429 wow.command.busy, 9,366 × 504 wow.wait.timeout, 135 × 503 wow.command.unknown). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 838.8 | 1.08 | 3.98 | 25,163 | 0 | 0.17 | 0.20 | 0.15 / 0.26 | 2.7 | 20.6 |  |
| 4 | 2,044.7 | 1.70 | 5.44 | 61,340 | 0 | 0.28 | 0.07 | 0.06 / 0.09 | 3.9 | 14.0 |  |
| 16 | 4,744.1 | 3.21 | 6.78 | 142,323 | 0 | 0.74 | 0.16 | 0.08 / 0.13 | 10.2 | 10.3 |  |
| 64 | 8,856.8 | 6.50 | 14.27 | 265,705 | 0 | 1.11 | 0.19 | 0.17 / 0.26 | 12.4 | 16.6 |  |
| 256 | 15,320.7 | 16.32 | 30.08 | 459,622 | 0 | 1.59 | 0.21 | 0.08 / 0.14 | 13.7 | 23.9 |  |
| 1,024 | 19,274.8 | 38.40 | 143.36 | 578,244 | 0 | 1.88 | 0.21 | 0.14 / 0.19 | 14.7 | 29.9 |  |
| 4,096 | 9,837.1 | 53.76 | 917.50 | 295,113 | 24,075 × http-429-wow.command.busy, 9,366 × http-504-wow.wait.timeout, 135 × http-503-wow.command.unknown | 1.18 | 0.16 | 0.12 / 0.18 | 14.1 | 34.7 |  |

Load averages before: 25.56 14.30 10.84; after: 31.61 20.80 14.26.

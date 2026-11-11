# 1-S-postgres/e1-zipf-sweep: E1 (zipf), 1 node(s) on postgres

Peak 16,139.1/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 15,417.2/s at 256 clients, p99 45.3 ms.
Failed more than 1% (not a peak): 4,096 clients: 19,902.2/s answered, failed 12.8% (87,288 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 1,153.6 | 0.79 | 1.94 | 34,607 | 0 | 0.13 | 0.04 | 0.74 / 0.76 | 2.7 | 20.2 |  |
| 4 | 2,081.1 | 1.74 | 4.83 | 62,434 | 0 | 0.24 | 0.07 | 0.78 / 0.83 | 3.8 | 13.7 |  |
| 16 | 2,858.9 | 4.22 | 16.89 | 85,768 | 0 | 0.36 | 0.07 | 0.91 / 1.00 | 5.7 | 11.0 |  |
| 64 | 10,393.1 | 5.41 | 15.62 | 311,794 | 0 | 1.09 | 0.16 | 0.90 / 1.02 | 9.7 | 10.3 |  |
| 256 | 15,417.2 | 15.10 | 45.31 | 462,516 | 0 | 1.51 | 0.21 | 0.89 / 0.92 | 10.4 | 13.4 |  |
| 1,024 | 16,139.1 | 17.79 | 423.94 | 484,172 | 0 | 1.56 | 0.20 | 0.91 / 0.98 | 9.6 | 20.9 |  |
| 4,096 | 19,902.2 | 67.07 | 1941.50 | 597,066 | 87,288 × http-429-wow.command.busy | 1.93 | 0.29 | 1.10 / 1.44 | 11.7 | 19.4 |  |

Load averages before: 24.74 15.04 7.86; after: 19.95 15.31 9.71.

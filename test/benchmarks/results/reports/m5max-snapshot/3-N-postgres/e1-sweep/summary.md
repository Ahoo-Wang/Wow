# 3-N-postgres/e1-sweep: E1 (uniform), 3 node(s) on postgres

Peak 21,114.1/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 21,114.1/s at 1,024 clients, p99 122.9 ms.
Failed more than 1% (not a peak): 4,096 clients: 10,824.4/s answered, failed 2.4% (7,681 × 504 wow.wait.timeout, 232 × 503 wow.command.unknown, 2 × 409 wow.command.contended). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 722.8 | 1.16 | 3.44 | 21,685 | 0 | 0.17 | 0.05 | 0.16 / 0.22 | 1.6 | 16.6 |  |
| 4 | 1,525.9 | 1.90 | 6.94 | 45,777 | 0 | 0.21 | 0.06 | 0.07 / 0.11 | 4.0 | 11.4 |  |
| 16 | 2,618.5 | 4.86 | 15.49 | 78,554 | 0 | 0.41 | 0.08 | 0.07 / 0.11 | 5.5 | 10.7 |  |
| 64 | 7,644.9 | 6.78 | 16.89 | 229,348 | 0 | 0.91 | 0.17 | 0.22 / 0.47 | 12.2 | 16.9 |  |
| 256 | 15,953.2 | 15.68 | 28.29 | 478,595 | 0 | 1.54 | 0.24 | 0.07 / 0.09 | 14.6 | 28.9 |  |
| 1,024 | 21,114.1 | 35.58 | 122.88 | 633,422 | 0 | 1.85 | 0.28 | 0.09 / 0.16 | 15.1 | 39.3 |  |
| 4,096 | 10,824.4 | 49.15 | 3227.65 | 324,733 | 7,681 × http-504-wow.wait.timeout, 232 × http-503-wow.command.unknown, 2 × http-409-wow.command.contended | 1.05 | 0.14 | 0.07 / 0.12 | 14.4 | 40.2 |  |

Load averages before: 20.30 13.88 11.81; after: 29.68 21.74 15.52.

# 1-S-mongo/e3-sweep: E3 (uniform), 1 node(s) on mongo

Peak 7,446.5/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 4,436.5/s at 256 clients, p99 135.2 ms.
Failed more than 1% (not a peak): 4,096 clients: 3,541.6/s answered, failed 4.5% (5,058 × 504 wow.wait.timeout, 4 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 208.3 | 4.32 | 11.07 | 6,250 | 0 | 0.12 | 0.06 | 0.71 / 0.78 | 3.6 | 20.4 |  |
| 4 | 586.7 | 6.37 | 14.21 | 17,600 | 0 | 0.12 | 0.04 | 0.79 / 0.83 | 3.1 | 14.6 |  |
| 16 | 1,218.3 | 10.88 | 38.91 | 36,550 | 0 | 0.16 | 0.03 | 0.40 / 0.75 | 3.8 | 13.5 |  |
| 64 | 3,118.8 | 18.82 | 41.73 | 93,563 | 0 | 0.28 | 0.04 | 0.09 / 0.17 | 4.8 | 12.2 |  |
| 256 | 4,436.5 | 53.76 | 135.17 | 133,096 | 0 | 0.42 | 0.07 | 0.04 / 0.06 | 7.8 | 15.8 |  |
| 1,024 | 7,446.5 | 129.02 | 325.63 | 223,395 | 0 | 0.57 | 0.15 | 0.13 / 0.37 | 9.9 | 17.7 |  |
| 4,096 | 3,541.6 | 204.80 | 8454.14 | 106,249 | 5,058 × http-504-wow.wait.timeout, 4 × http-429-wow.command.busy | 0.28 | 0.04 | 0.06 / 0.12 | 12.3 | 16.6 |  |

Load averages before: 23.80 13.89 11.84; after: 14.51 13.39 12.05.

# e1-hot-arms/5-old-25b/e1-hot-sweep: E1 (hot), 1 node(s) on postgres

Peak 11,875.7/s at 256 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 11,875.7/s at 256 clients, p99 58.1 ms.
Failed more than 1% (not a peak): 4,096 clients: 18,942.9/s answered, failed 47.4% (511,403 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 334.4 | 2.61 | 6.30 | 10,032 | 0 | 0.14 | 0.04 | 1.42 / 1.58 | 2.0 | 11.6 |  |
| 4 | 748.3 | 4.19 | 14.08 | 22,449 | 0 | 0.13 | 0.03 | 1.13 / 1.29 | 2.8 | 9.0 |  |
| 16 | 2,579.3 | 4.77 | 17.28 | 77,379 | 0 | 0.34 | 0.08 | 1.02 / 1.18 | 5.4 | 8.4 |  |
| 64 | 5,906.4 | 8.26 | 166.91 | 177,191 | 0 | 0.62 | 0.09 | 0.87 / 1.01 | 4.5 | 6.8 |  |
| 256 | 11,875.7 | 26.37 | 58.11 | 356,271 | 0 | 1.21 | 0.17 | 1.26 / 1.88 | 7.5 | 12.0 |  |
| 1,024 | 9,907.9 | 142.34 | 403.45 | 297,238 | 0 | 1.02 | 0.14 | 1.46 / 1.86 | 5.4 | 11.3 |  |
| 4,096 | 18,942.9 | 95.23 | 1302.53 | 568,286 | 511,403 × http-429-wow.command.busy | 3.30 | 0.44 | 0.99 / 1.46 | 12.7 | 16.9 |  |

Load averages before: 13.70 11.74 10.63; after: 18.46 12.07 10.77.

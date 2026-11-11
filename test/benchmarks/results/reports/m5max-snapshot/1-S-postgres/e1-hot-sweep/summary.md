# 1-S-postgres/e1-hot-sweep: E1 (hot), 1 node(s) on postgres

Peak 9,431.6/s at 256 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 9,431.6/s at 256 clients, p99 149.5 ms.
Failed more than 1% (not a peak): 4,096 clients: 19,455.5/s answered, failed 47.7% (532,704 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 728.1 | 1.00 | 4.01 | 21,842 | 0 | 0.11 | 0.02 | 1.01 / 1.17 | 2.4 | 9.6 |  |
| 4 | 1,992.8 | 1.81 | 5.28 | 59,784 | 0 | 0.24 | 0.06 | 0.71 / 0.94 | 4.5 | 6.6 |  |
| 16 | 2,197.3 | 5.63 | 23.55 | 65,918 | 0 | 0.28 | 0.05 | 0.71 / 0.75 | 3.0 | 8.3 |  |
| 64 | 6,514.0 | 8.03 | 49.41 | 195,419 | 0 | 0.67 | 0.10 | 0.84 / 0.89 | 5.0 | 9.5 |  |
| 256 | 9,431.6 | 30.72 | 149.50 | 282,949 | 0 | 0.97 | 0.14 | 1.38 / 2.19 | 6.8 | 9.3 |  |
| 1,024 | 8,446.8 | 146.43 | 405.50 | 253,405 | 0 | 0.86 | 0.12 | 0.82 / 0.98 | 6.2 | 10.0 |  |
| 4,096 | 19,455.5 | 96.25 | 1540.10 | 583,664 | 532,704 × http-429-wow.command.busy | 3.28 | 0.46 | 0.80 / 0.87 | 13.5 | 12.0 |  |

Load averages before: 10.10 11.59 9.16; after: 13.70 10.26 9.09.

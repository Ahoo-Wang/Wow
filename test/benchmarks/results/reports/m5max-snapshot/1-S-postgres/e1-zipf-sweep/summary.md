# 1-S-postgres/e1-zipf-sweep: E1 (zipf), 1 node(s) on postgres

Peak 15,112.4/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 15,063.1/s at 256 clients, p99 45.1 ms.
Failed more than 1% (not a peak): 4,096 clients: 19,754.8/s answered, failed 13.8% (95,019 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 1,066.1 | 0.90 | 1.88 | 31,983 | 0 | 0.19 | 0.05 | 0.54 / 0.84 | 2.2 | 14.6 |  |
| 4 | 2,437.9 | 1.56 | 3.13 | 73,136 | 0 | 0.28 | 0.09 | 0.12 / 0.20 | 3.2 | 10.3 |  |
| 16 | 5,328.7 | 2.73 | 6.53 | 159,860 | 0 | 0.68 | 0.12 | 0.09 / 0.16 | 5.4 | 11.2 |  |
| 64 | 9,290.6 | 5.79 | 18.94 | 278,718 | 0 | 1.00 | 0.14 | 0.15 / 0.19 | 5.7 | 12.6 |  |
| 256 | 15,063.1 | 15.49 | 45.05 | 451,894 | 0 | 1.60 | 0.21 | 0.12 / 0.17 | 8.3 | 17.7 |  |
| 1,024 | 15,112.4 | 18.30 | 565.25 | 453,372 | 0 | 1.53 | 0.22 | 0.15 / 0.22 | 8.8 | 18.0 |  |
| 4,096 | 19,754.8 | 60.93 | 1810.43 | 592,643 | 95,019 × http-429-wow.command.busy | 2.08 | 0.28 | 0.13 / 0.18 | 11.0 | 25.0 |  |

Load averages before: 18.17 14.95 9.30; after: 26.29 17.35 11.61.

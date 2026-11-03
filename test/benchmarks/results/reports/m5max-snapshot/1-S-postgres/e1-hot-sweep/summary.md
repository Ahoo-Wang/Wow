# 1-S-postgres/e1-hot-sweep: E1 (hot), 1 node(s) on postgres

Peak 12,351.4/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 8,000.2/s at 256 clients, p99 155.6 ms.
Failed more than 1% (not a peak): 4,096 clients: 23,768.6/s answered, failed 48.9% (681,803 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 947.5 | 0.97 | 2.72 | 28,425 | 0 | 0.19 | 0.02 | 0.59 / 1.31 | 1.8 | 6.6 |  |
| 4 | 2,099.0 | 1.80 | 4.42 | 62,969 | 0 | 0.27 | 0.15 | 0.08 / 0.10 | 3.0 | 4.4 |  |
| 16 | 4,614.9 | 3.17 | 7.68 | 138,448 | 0 | 0.57 | 0.11 | 0.07 / 0.10 | 4.3 | 5.8 |  |
| 64 | 8,851.6 | 6.43 | 18.82 | 265,547 | 0 | 0.94 | 0.13 | 0.08 / 0.14 | 5.8 | 7.4 |  |
| 256 | 8,000.2 | 30.46 | 155.65 | 240,005 | 0 | 0.84 | 0.13 | 0.15 / 0.33 | 6.0 | 9.6 |  |
| 1,024 | 12,351.4 | 133.12 | 331.77 | 370,541 | 0 | 1.29 | 0.17 | 0.08 / 0.10 | 6.7 | 7.7 |  |
| 4,096 | 23,768.6 | 94.72 | 1875.97 | 713,059 | 681,803 × http-429-wow.command.busy | 3.95 | 0.49 | 0.09 / 0.11 | 13.2 | 12.7 |  |

Load averages before: 7.54 11.42 10.17; after: 13.76 9.25 9.09.

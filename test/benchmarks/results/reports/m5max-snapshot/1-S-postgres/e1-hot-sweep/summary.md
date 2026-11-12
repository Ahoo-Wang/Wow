# 1-S-postgres/e1-hot-sweep: E1 (hot), 1 node(s) on postgres

Peak 11,521.2/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 8,520.7/s at 256 clients, p99 155.6 ms.
Failed more than 1% (not a peak): 4,096 clients: 21,779.9/s answered, failed 48.1% (605,525 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 639.1 | 1.20 | 4.03 | 19,173 | 0 | 0.13 | 0.03 | 0.17 / 0.40 | 1.5 | 12.4 |  |
| 4 | 729.5 | 4.77 | 15.81 | 21,886 | 0 | 0.14 | 0.04 | 0.15 / 0.28 | 1.9 | 4.4 |  |
| 16 | 2,955.3 | 4.22 | 16.19 | 88,659 | 0 | 0.34 | 0.07 | 0.12 / 0.24 | 3.2 | 6.1 |  |
| 64 | 7,664.9 | 7.01 | 22.27 | 229,946 | 0 | 0.76 | 0.11 | 0.05 / 0.11 | 5.2 | 6.7 |  |
| 256 | 8,520.7 | 30.85 | 155.65 | 255,620 | 0 | 0.85 | 0.13 | 0.07 / 0.09 | 5.5 | 6.9 |  |
| 1,024 | 11,521.2 | 134.14 | 303.10 | 345,635 | 0 | 1.15 | 0.16 | 0.09 / 0.13 | 7.2 | 5.6 |  |
| 4,096 | 21,779.9 | 88.06 | 1548.29 | 653,396 | 605,525 × http-429-wow.command.busy | 3.53 | 0.43 | 0.18 / 0.41 | 13.3 | 11.2 |  |

Load averages before: 13.93 9.99 9.81; after: 12.74 8.40 8.78.

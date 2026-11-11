# 1-S-mongo/e1-zipf-sweep: E1 (zipf), 1 node(s) on mongo

Peak 14,536.9/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 12,643.0/s at 256 clients, p99 68.6 ms.
Failed more than 1% (not a peak): 4,096 clients: 16,731.3/s answered, failed 13.5% (78,298 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 324.1 | 2.83 | 6.82 | 9,722 | 0 | 0.16 | 0.06 | 0.81 / 0.84 | 2.5 | 17.4 |  |
| 4 | 973.0 | 3.04 | 11.97 | 29,189 | 0 | 0.16 | 0.04 | 1.13 / 1.93 | 2.7 | 11.2 |  |
| 16 | 2,057.4 | 5.73 | 22.78 | 61,722 | 0 | 0.25 | 0.06 | 0.83 / 0.94 | 4.3 | 7.0 |  |
| 64 | 7,096.1 | 7.65 | 23.81 | 212,882 | 0 | 0.66 | 0.09 | 0.74 / 0.77 | 5.2 | 6.2 |  |
| 256 | 12,643.0 | 17.79 | 68.61 | 379,290 | 0 | 1.14 | 0.16 | 0.72 / 0.74 | 7.6 | 8.8 |  |
| 1,024 | 14,536.9 | 21.12 | 454.65 | 436,108 | 0 | 1.30 | 0.14 | 0.74 / 0.77 | 8.0 | 11.0 |  |
| 4,096 | 16,731.3 | 72.19 | 2072.57 | 501,940 | 78,298 × http-429-wow.command.busy | 1.57 | 0.21 | 0.75 / 0.77 | 10.5 | 19.1 |  |

Load averages before: 21.06 16.21 12.33; after: 19.12 13.46 11.78.

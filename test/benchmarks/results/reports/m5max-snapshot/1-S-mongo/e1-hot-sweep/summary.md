# 1-S-mongo/e1-hot-sweep: E1 (hot), 1 node(s) on mongo

Peak 6,911.2/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 5,417.2/s at 64 clients, p99 150.5 ms.
Failed more than 1% (not a peak): 4,096 clients: 18,645.7/s answered, failed 49.5% (548,113 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 344.2 | 2.85 | 5.73 | 10,326 | 0 | 0.14 | 0.02 | 0.14 / 0.31 | 1.3 | 11.0 |  |
| 4 | 829.3 | 3.73 | 12.54 | 24,879 | 0 | 0.14 | 0.04 | 0.35 / 0.69 | 2.3 | 3.8 |  |
| 16 | 2,203.6 | 5.60 | 20.86 | 66,108 | 0 | 0.27 | 0.05 | 0.77 / 0.81 | 3.5 | 4.0 |  |
| 64 | 5,417.2 | 8.96 | 150.53 | 162,517 | 0 | 0.53 | 0.08 | 0.76 / 0.83 | 6.3 | 6.2 |  |
| 256 | 6,687.8 | 37.63 | 248.83 | 200,635 | 0 | 0.65 | 0.10 | 0.86 / 1.10 | 5.5 | 7.2 |  |
| 1,024 | 6,911.2 | 187.39 | 471.04 | 207,336 | 0 | 0.68 | 0.10 | 0.74 / 0.76 | 5.6 | 7.9 |  |
| 4,096 | 18,645.7 | 155.65 | 4653.06 | 559,370 | 548,113 × http-429-wow.command.busy | 2.87 | 0.45 | 0.76 / 0.84 | 13.7 | 16.3 |  |

Load averages before: 12.18 11.67 11.16; after: 23.15 11.44 10.56.

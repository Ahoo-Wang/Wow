# e1-hot-arms/1-head-25/e1-hot-sweep: E1 (hot), 1 node(s) on postgres

Peak 10,643.1/s at 64 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 9,490.9/s at 256 clients, p99 148.5 ms.
Failed more than 1% (not a peak): 4,096 clients: 19,640.4/s answered, failed 47.0% (521,920 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 955.7 | 0.83 | 3.20 | 28,671 | 0 | 0.11 | 0.03 | 1.06 / 1.92 | 3.1 | 7.9 |  |
| 4 | 1,330.7 | 2.08 | 9.28 | 39,920 | 0 | 0.18 | 0.06 | 0.95 / 1.10 | 3.2 | 8.6 |  |
| 16 | 4,270.9 | 3.23 | 10.43 | 128,128 | 0 | 0.54 | 0.12 | 0.93 / 1.88 | 5.7 | 9.8 |  |
| 64 | 10,643.1 | 5.76 | 13.76 | 319,293 | 0 | 1.08 | 0.16 | 1.32 / 2.50 | 7.4 | 17.4 |  |
| 256 | 9,490.9 | 28.80 | 148.48 | 284,727 | 0 | 0.97 | 0.14 | 0.53 / 0.59 | 6.3 | 14.7 |  |
| 1,024 | 9,654.8 | 139.26 | 323.58 | 289,645 | 0 | 0.98 | 0.21 | 0.79 / 1.14 | 8.3 | 14.9 |  |
| 4,096 | 19,640.4 | 95.23 | 1245.18 | 589,212 | 521,920 × http-429-wow.command.busy | 3.25 | 0.40 | 1.24 / 1.71 | 12.6 | 17.2 |  |

Load averages before: 6.64 12.77 16.33; after: 17.65 14.23 15.83.

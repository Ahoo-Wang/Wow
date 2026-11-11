# 1-S-postgres/e1-hot-top: E1 (hot), 1 node(s) on postgres

Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 4,096 clients: 19,078.7/s answered, failed 48.0% (527,870 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 19,078.7 | 99.33 | 1572.86 | 572,360 | 527,870 × http-429-wow.command.busy | 3.23 | 0.43 | 0.86 / 0.94 | 13.3 | 19.2 |  |

Load averages before: 13.70 10.26 9.09; after: 19.79 12.27 9.89.

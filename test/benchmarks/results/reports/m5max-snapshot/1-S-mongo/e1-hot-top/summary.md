# 1-S-mongo/e1-hot-top: E1 (hot), 1 node(s) on mongo

Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 4,096 clients: 18,025.7/s answered, failed 49.5% (530,249 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 18,025.7 | 160.77 | 4849.66 | 540,770 | 530,249 × http-429-wow.command.busy | 2.79 | 0.43 | 0.91 / 1.32 | 13.7 | 22.0 |  |

Load averages before: 19.45 11.88 11.07; after: 23.80 13.89 11.84.

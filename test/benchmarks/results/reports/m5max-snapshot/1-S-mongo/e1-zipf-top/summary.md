# 1-S-mongo/e1-zipf-top: E1 (zipf), 1 node(s) on mongo

Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 4,096 clients: 16,332.9/s answered, failed 13.1% (73,997 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 16,332.9 | 74.75 | 2088.96 | 489,987 | 73,997 × http-429-wow.command.busy | 1.53 | 0.10 | 0.80 / 0.97 | 5.9 | 16.2 |  |

Load averages before: 18.47 13.42 11.78; after: 15.83 12.87 11.68.

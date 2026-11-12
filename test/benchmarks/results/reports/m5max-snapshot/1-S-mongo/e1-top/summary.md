# 1-S-mongo/e1-top: E1 (uniform), 1 node(s) on mongo

Peak 21,822.6/s at 4,096 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 8,192 clients: 17,754.7/s answered, failed 16.4% (104,730 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 21,822.6 | 177.15 | 503.81 | 654,679 | 0 | 1.56 | 0.16 | 0.34 / 0.39 | 8.7 | 22.6 |  |
| 8,192 | 17,754.7 | 423.94 | 913.41 | 532,642 | 104,730 × http-429-wow.command.busy | 2.09 | 0.17 | 0.32 / 0.42 | 11.4 | 24.0 |  |

Load averages before: 20.10 13.78 11.13; after: 22.00 16.03 12.25.

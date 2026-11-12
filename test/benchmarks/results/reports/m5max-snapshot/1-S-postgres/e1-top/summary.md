# 1-S-postgres/e1-top: E1 (uniform), 1 node(s) on postgres

Peak 36,719.5/s at 4,096 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 8,192 clients: 21,273.0/s answered, failed 4.0% (26,600 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 36,719.5 | 108.54 | 227.33 | 1,101,586 | 0 | 2.84 | 0.40 | 0.05 / 0.09 | 14.8 | 19.5 |  |
| 8,192 | 21,273.0 | 376.83 | 557.05 | 638,191 | 26,600 × http-429-wow.command.busy | 2.22 | 0.15 | 0.09 / 0.18 | 9.5 | 22.1 |  |

Load averages before: 22.76 12.38 10.15; after: 22.93 14.64 11.20.

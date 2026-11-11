# 1-S-postgres/e1-top: E1 (uniform), 1 node(s) on postgres

Peak 32,879.1/s at 4,096 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 32,879.1 | 121.86 | 236.54 | 986,374 | 0 | 2.62 | 0.37 | 1.18 / 1.68 | 14.4 | 23.8 |  |
| 8,192 | 18,695.6 | 436.22 | 569.34 | 560,869 | 2,993 × http-429-wow.command.busy | 2.06 | 0.16 | 1.81 / 3.64 | 10.0 | 25.2 |  |

Load averages before: 23.57 11.69 6.05; after: 24.74 15.04 7.86.

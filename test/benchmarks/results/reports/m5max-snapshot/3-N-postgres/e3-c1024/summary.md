# 3-N-postgres/e3-c1024: E3 (uniform), 3 node(s) on postgres

Result 5,807.8/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1,024 | 5,807.8 | 130.56 | 806.91 | 348,470 | 0 | 0.75 | 0.13 | 0.36 / 1.39 | 13.4 | 37.0 |  |

Load averages before: 35.72 28.38 20.21; after: 24.94 27.40 20.61.

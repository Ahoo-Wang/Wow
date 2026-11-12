# 3-N-postgres/e1-c1024: E1 (uniform), 3 node(s) on postgres

Result 19,000.2/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 19,000.2/s at 1,024 clients, p99 180.2 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1,024 | 19,000.2 | 36.86 | 180.22 | 1,140,010 | 0 | 1.64 | 0.23 | 0.12 / 0.29 | 13.5 | 39.6 |  |

Load averages before: 44.39 27.88 19.30; after: 35.72 28.38 20.21.

# 3-N-postgres/e1-c1024: E1 (uniform), 3 node(s) on postgres

Result 20,698.7/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 20,698.7/s at 1,024 clients, p99 127.0 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1,024 | 20,698.7 | 36.09 | 126.97 | 1,241,923 | 0 | 1.80 | 0.21 | 0.06 / 0.17 | 12.8 | 31.3 |  |

Load averages before: 28.57 24.00 18.63; after: 33.44 25.48 19.69.

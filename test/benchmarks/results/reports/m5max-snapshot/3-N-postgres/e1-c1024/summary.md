# 3-N-postgres/e1-c1024: E1 (uniform), 3 node(s) on postgres

Result 24,255.1/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 24,255.1/s at 1,024 clients, p99 98.8 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1,024 | 24,255.1 | 32.13 | 98.81 | 1,455,307 | 0 | 2.11 | 0.30 | 0.14 / 0.36 | 15.8 | – | 47.3 |  |

Load averages before: 40.78 29.43 20.46; after: 46.75 33.84 22.95.

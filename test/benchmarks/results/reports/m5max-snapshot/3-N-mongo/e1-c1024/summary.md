# 3-N-mongo/e1-c1024: E1 (uniform), 3 node(s) on mongo

Result 15,805.8/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 15,805.8/s at 1,024 clients, p99 192.5 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1,024 | 15,805.8 | 48.90 | 192.51 | 948,347 | 0 | 1.24 | 0.20 | 0.09 / 0.15 | 13.4 | 38.6 |  |

Load averages before: 26.05 24.81 22.10; after: 38.95 29.15 23.99.

# 3-N-mongo/e3-c1024: E3 (uniform), 3 node(s) on mongo

Result 4,769.1/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1,024 | 4,769.1 | 162.81 | 626.69 | 286,149 | 0 | 0.58 | 0.09 | 0.86 / 1.21 | 13.9 | 44.5 |  |

Load averages before: 22.36 24.03 19.48; after: 37.67 29.04 21.79.

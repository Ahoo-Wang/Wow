# naming-mongo/e1-uniform-4: E1 (uniform), 1 node(s) on mongo

Peak 1,432.1/s at 4 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 1,318.6/s at 4 clients, p99 7.6 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4 | 1,318.6 | 2.65 | 7.55 | 13,186 | 0 | 0.35 | – | – | – | – |  |
| 4 | 976.2 | 3.25 | 12.29 | 9,762 | 0 | 0.16 | – | – | – | – |  |
| 4 | 1,417.9 | 2.38 | 7.01 | 14,179 | 0 | 0.22 | – | – | – | – |  |
| 4 | 1,067.7 | 3.04 | 9.73 | 10,677 | 0 | 0.17 | – | – | – | – |  |
| 4 | 1,414.5 | 2.38 | 7.17 | 14,145 | 0 | 0.21 | – | – | – | – |  |
| 4 | 1,378.2 | 2.40 | 7.46 | 13,782 | 0 | 0.21 | – | – | – | – |  |
| 4 | 818.9 | 4.25 | 11.90 | 8,189 | 0 | 0.15 | – | – | – | – |  |
| 4 | 825.6 | 4.22 | 14.14 | 8,256 | 0 | 0.15 | – | – | – | – |  |
| 4 | 835.6 | 4.16 | 12.48 | 8,356 | 0 | 0.16 | – | – | – | – |  |
| 4 | 1,432.1 | 2.35 | 6.82 | 14,321 | 0 | 0.22 | – | – | – | – |  |
| 4 | 1,153.2 | 2.83 | 9.21 | 11,532 | 0 | 0.19 | – | – | – | – |  |
| 4 | 880.1 | 3.79 | 11.33 | 8,801 | 0 | 0.13 | – | – | – | – |  |

Load averages before: 7.89 4.50 5.34; after: 6.61 5.54 5.63.

# 3-N-mongo/e1-sweep: E1 (uniform), 3 node(s) on mongo

Peak 14,778.8/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 12,128.7/s at 256 clients, p99 47.4 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 317.6 | 2.98 | 6.50 | 9,528 | 0 | 0.12 | 0.09 | 1.26 / 2.05 | 2.4 | 13.6 |  |
| 4 | 1,325.8 | 2.56 | 7.17 | 39,774 | 0 | 0.19 | 0.05 | 0.64 / 0.73 | 4.2 | 9.2 |  |
| 16 | 2,484.9 | 5.38 | 17.41 | 74,546 | 0 | 0.36 | 0.07 | 0.92 / 0.99 | 7.4 | 8.8 |  |
| 64 | 7,114.4 | 7.93 | 20.86 | 213,433 | 0 | 0.78 | 0.13 | 0.62 / 0.74 | 11.1 | 15.2 |  |
| 256 | 12,128.7 | 19.84 | 47.36 | 363,860 | 0 | 1.05 | 0.17 | 1.08 / 1.85 | 13.0 | 17.2 |  |
| 1,024 | 14,778.8 | 52.22 | 204.80 | 443,364 | 0 | 1.21 | 0.17 | 0.86 / 1.10 | 14.4 | 29.8 |  |
| 4,096 | 6,998.7 | 183.29 | 4489.22 | 209,961 | 0 | 0.58 | 0.08 | 0.74 / 0.88 | 15.6 | 36.5 |  |

Load averages before: 15.10 10.69 14.07; after: 34.96 19.37 16.41.

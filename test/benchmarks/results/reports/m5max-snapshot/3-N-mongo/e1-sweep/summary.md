# 3-N-mongo/e1-sweep: E1 (uniform), 3 node(s) on mongo

Peak 15,341.3/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 15,341.3/s at 1,024 clients, p99 194.6 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 324.6 | 2.96 | 6.30 | 9,737 | 0 | 0.10 | 0.05 | 0.13 / 0.17 | 1.1 | 23.5 |  |
| 4 | 996.8 | 2.98 | 9.09 | 29,905 | 0 | 0.14 | 0.04 | 0.16 / 0.22 | 3.6 | 14.9 |  |
| 16 | 3,360.2 | 4.22 | 11.65 | 100,806 | 0 | 0.48 | 0.11 | 0.09 / 0.16 | 8.1 | 13.0 |  |
| 64 | 6,832.8 | 8.03 | 22.66 | 204,985 | 0 | 0.78 | 0.13 | 0.39 / 0.82 | 10.3 | 14.6 |  |
| 256 | 12,318.5 | 19.33 | 47.10 | 369,554 | 0 | 1.15 | 0.15 | 0.10 / 0.11 | 12.5 | 20.0 |  |
| 1,024 | 15,341.3 | 51.20 | 194.56 | 460,239 | 0 | 1.35 | 0.19 | 0.11 / 0.15 | 13.9 | 25.7 |  |
| 4,096 | 6,580.8 | 202.75 | 5242.88 | 197,423 | 0 | 0.64 | 0.07 | 0.10 / 0.18 | 14.5 | 36.5 |  |

Load averages before: 29.50 26.13 19.89; after: 32.79 24.69 20.35.

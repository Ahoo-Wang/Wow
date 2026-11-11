# 1-S-mongo/e1-sweep: E1 (uniform), 1 node(s) on mongo

Peak ≥ 21,458.6/s at 4,096 clients (its last step: a lower bound).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 17,946.6/s at 1,024 clients, p99 135.2 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 324.9 | 2.83 | 6.75 | 9,748 | 0 | 0.15 | 0.03 | 1.01 / 1.06 | 2.4 | 8.3 |  |
| 4 | 1,051.4 | 2.94 | 9.98 | 31,543 | 0 | 0.16 | 0.04 | 0.95 / 1.18 | 2.9 | 6.6 |  |
| 16 | 3,643.8 | 3.81 | 11.33 | 109,313 | 0 | 0.39 | 0.08 | 0.81 / 0.87 | 5.0 | 6.9 |  |
| 64 | 5,863.7 | 8.45 | 52.22 | 175,910 | 0 | 0.53 | 0.08 | 0.78 / 0.82 | 5.0 | 9.6 |  |
| 256 | 12,751.6 | 19.20 | 47.36 | 382,548 | 0 | 1.13 | 0.15 | 0.77 / 0.83 | 8.0 | 13.3 |  |
| 1,024 | 17,946.6 | 55.81 | 135.17 | 538,397 | 0 | 1.44 | 0.20 | 1.01 / 1.74 | 10.3 | 22.8 |  |
| 4,096 | 21,458.6 | 186.37 | 366.59 | 643,759 | 0 | 1.55 | 0.20 | 0.77 / 0.89 | 12.5 | 27.2 |  |

Load averages before: 9.74 10.40 9.75; after: 24.67 14.93 11.52.

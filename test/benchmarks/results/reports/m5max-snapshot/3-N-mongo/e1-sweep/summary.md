# 3-N-mongo/e1-sweep: E1 (uniform), 3 node(s) on mongo

Peak 16,657.2/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 16,657.2/s at 1,024 clients, p99 178.2 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 289.4 | 3.12 | 7.26 | 8,682 | 0 | 0.14 | 0.02 | 0.14 / 0.22 | 1.8 | 28.9 |  |
| 4 | 941.0 | 3.29 | 10.11 | 28,230 | 0 | 0.15 | 0.04 | 0.10 / 0.17 | 2.8 | 17.5 |  |
| 16 | 2,455.2 | 5.12 | 17.41 | 73,655 | 0 | 0.35 | 0.07 | 0.12 / 0.15 | 5.6 | 12.0 |  |
| 64 | 6,691.8 | 7.58 | 30.34 | 200,754 | 0 | 0.71 | 0.12 | 0.12 / 0.17 | 10.9 | 18.8 |  |
| 256 | 13,448.1 | 17.92 | 42.24 | 403,443 | 0 | 1.15 | 0.16 | 0.10 / 0.13 | 13.5 | 30.1 |  |
| 1,024 | 16,657.2 | 46.85 | 178.18 | 499,716 | 0 | 1.30 | 0.20 | 0.10 / 0.14 | 14.3 | 33.4 |  |
| 4,096 | 7,741.7 | 157.69 | 3375.10 | 232,251 | 0 | 0.63 | 0.09 | 0.12 / 0.17 | 14.6 | 35.7 |  |

Load averages before: 35.34 28.70 21.55; after: 35.71 27.35 22.27.

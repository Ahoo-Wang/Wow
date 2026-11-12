# 1-S-postgres/e1-sweep: E1 (uniform), 1 node(s) on postgres

Peak ≥ 37,713.6/s at 4,096 clients (its last step: a lower bound).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 23,030.1/s at 1,024 clients, p99 74.8 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 776.3 | 0.88 | 3.62 | 23,290 | 0 | 0.17 | 0.13 | 0.11 / 0.15 | 1.6 | 7.8 |  |
| 4 | 684.4 | 4.93 | 16.13 | 20,532 | 0 | 0.13 | 0.05 | 0.07 / 0.10 | 2.5 | 6.6 |  |
| 16 | 2,643.5 | 4.89 | 15.29 | 79,306 | 0 | 0.33 | 0.07 | 0.24 / 0.61 | 3.5 | 6.3 |  |
| 64 | 9,676.4 | 5.38 | 15.94 | 290,292 | 0 | 0.95 | 0.11 | 0.13 / 0.16 | 6.1 | 5.5 |  |
| 256 | 16,241.7 | 16.13 | 28.54 | 487,251 | 0 | 1.49 | 0.20 | 0.07 / 0.14 | 9.1 | 8.1 |  |
| 1,024 | 23,030.1 | 48.90 | 74.75 | 690,904 | 0 | 1.93 | 0.27 | 0.05 / 0.09 | 10.1 | 11.6 |  |
| 4,096 | 37,713.6 | 105.98 | 211.97 | 1,131,408 | 0 | 2.87 | 0.39 | 0.26 / 0.59 | 14.2 | 22.4 |  |

Load averages before: 9.35 9.98 9.30; after: 22.76 12.38 10.15.

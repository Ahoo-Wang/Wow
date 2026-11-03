# 1-S-postgres/e1-sweep: E1 (uniform), 1 node(s) on postgres

Peak ≥ 31,484.9/s at 4,096 clients (its last step: a lower bound).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 24,541.9/s at 1,024 clients, p99 95.2 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 1,058.5 | 0.93 | 1.67 | 31,755 | 0 | 0.12 | 0.04 | 0.47 / 0.71 | 2.4 | 7.2 |  |
| 4 | 2,399.4 | 1.59 | 3.09 | 71,982 | 0 | 0.28 | 0.08 | 0.29 / 0.46 | 4.0 | 7.6 |  |
| 16 | 5,543.3 | 2.72 | 5.86 | 166,299 | 0 | 0.65 | 0.12 | 0.77 / 1.32 | 5.9 | 6.9 |  |
| 64 | 9,855.7 | 5.38 | 15.62 | 295,672 | 0 | 1.03 | 0.16 | 0.50 / 0.52 | 7.2 | 7.3 |  |
| 256 | 16,411.8 | 15.62 | 25.98 | 492,355 | 0 | 1.63 | 0.21 | 0.19 / 0.38 | 8.2 | 15.3 |  |
| 1,024 | 24,541.9 | 40.70 | 95.23 | 736,258 | 0 | 2.27 | 0.29 | 0.18 / 0.31 | 10.7 | 20.3 |  |
| 4,096 | 31,484.9 | 124.42 | 296.96 | 944,546 | 0 | 2.68 | 0.32 | 0.40 / 0.66 | 12.5 | 22.1 |  |

Load averages before: 7.96 9.57 5.67; after: 22.14 13.61 8.24.

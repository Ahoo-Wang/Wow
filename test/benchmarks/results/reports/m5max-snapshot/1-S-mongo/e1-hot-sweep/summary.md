# 1-S-mongo/e1-hot-sweep: E1 (hot), 1 node(s) on mongo

Peak 1,634.7/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 1,456.7/s at 64 clients, p99 165.9 ms.
Failed more than 1% (not a peak): 4,096 clients: 18,301.3/s answered, failed 49.7% (543,008 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 326.3 | 2.90 | 6.72 | 9,788 | 0 | 0.12 | 0.06 | 0.56 / 0.59 | 1.4 | 3.3 |  |
| 4 | 774.1 | 4.05 | 14.40 | 23,222 | 0 | 0.10 | 0.04 | 0.11 / 0.18 | 2.1 | 3.5 |  |
| 16 | 1,088.4 | 11.71 | 48.90 | 32,651 | 0 | 0.13 | 0.03 | 0.14 / 0.28 | 1.7 | 3.6 |  |
| 64 | 1,456.7 | 45.31 | 165.89 | 43,702 | 0 | 0.16 | 0.03 | 0.13 / 0.22 | 1.9 | 4.5 |  |
| 256 | 1,558.3 | 50.17 | 581.63 | 46,749 | 0 | 0.16 | 0.03 | 0.17 / 0.24 | 2.2 | 4.7 |  |
| 1,024 | 1,634.7 | 52.48 | 1458.17 | 49,040 | 0 | 0.17 | 0.11 | 0.08 / 0.09 | 4.2 | 4.4 |  |
| 4,096 | 18,301.3 | 147.46 | 399.36 | 549,039 | 543,008 × http-429-wow.command.busy | 3.01 | 0.38 | 0.12 / 0.17 | 12.7 | 15.4 |  |

Load averages before: 3.70 6.91 8.65; after: 16.44 8.06 8.35.

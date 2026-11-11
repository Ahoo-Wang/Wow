# 1-S-mongo/e1-hot-sweep: E1 (hot), 1 node(s) on mongo

Peak 8,984.5/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 8,079.4/s at 256 clients, p99 147.5 ms.
Failed more than 1% (not a peak): 4,096 clients: 18,920.3/s answered, failed 49.4% (554,978 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 330.0 | 2.77 | 6.72 | 9,899 | 0 | 0.15 | 0.01 | 0.89 / 1.07 | 2.1 | 12.6 |  |
| 4 | 1,047.5 | 3.10 | 10.43 | 31,426 | 0 | 0.17 | 0.04 | 0.85 / 0.89 | 3.5 | 5.2 |  |
| 16 | 3,236.5 | 4.45 | 12.41 | 97,096 | 0 | 0.37 | 0.06 | 0.79 / 0.92 | 3.7 | 7.3 |  |
| 64 | 7,306.6 | 7.97 | 22.02 | 219,197 | 0 | 0.69 | 0.10 | 0.69 / 0.79 | 5.5 | 9.6 |  |
| 256 | 8,079.4 | 36.61 | 147.46 | 242,382 | 0 | 0.76 | 0.10 | 0.66 / 0.66 | 5.3 | 11.3 |  |
| 1,024 | 8,984.5 | 180.22 | 346.11 | 269,535 | 0 | 0.86 | 0.12 | 0.75 / 0.83 | 5.7 | 10.7 |  |
| 4,096 | 18,920.3 | 152.57 | 4358.14 | 567,610 | 554,978 × http-429-wow.command.busy | 2.92 | 0.44 | 0.69 / 0.71 | 13.9 | 13.3 |  |

Load averages before: 14.64 12.67 11.62; after: 19.45 11.88 11.07.

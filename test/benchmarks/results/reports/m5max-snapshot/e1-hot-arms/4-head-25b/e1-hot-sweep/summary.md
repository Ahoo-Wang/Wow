# e1-hot-arms/4-head-25b/e1-hot-sweep: E1 (hot), 1 node(s) on postgres

Peak 10,353.7/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 8,219.0/s at 64 clients, p99 20.5 ms.
Failed more than 1% (not a peak): 4,096 clients: 19,273.3/s answered, failed 47.4% (521,704 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 545.9 | 1.33 | 4.13 | 16,377 | 0 | 0.11 | 0.03 | 0.92 / 1.09 | 2.6 | 10.9 |  |
| 4 | 1,258.9 | 2.06 | 9.73 | 37,767 | 0 | 0.18 | 0.12 | 0.70 / 0.90 | 3.0 | 6.9 |  |
| 16 | 2,670.8 | 4.54 | 16.51 | 80,123 | 0 | 0.35 | 0.07 | 0.65 / 0.78 | 4.2 | 5.9 |  |
| 64 | 8,219.0 | 6.43 | 20.48 | 246,570 | 0 | 0.87 | 0.11 | 0.70 / 0.99 | 5.5 | 7.3 |  |
| 256 | 8,523.3 | 29.05 | 231.42 | 255,700 | 0 | 0.90 | 0.14 | 0.50 / 0.52 | 5.8 | 11.0 |  |
| 1,024 | 10,353.7 | 135.17 | 385.02 | 310,611 | 0 | 1.09 | 0.22 | 0.61 / 0.68 | 8.3 | 11.2 |  |
| 4,096 | 19,273.3 | 94.72 | 1376.26 | 578,199 | 521,704 × http-429-wow.command.busy | 3.31 | 0.46 | 0.93 / 1.12 | 13.2 | 16.5 |  |

Load averages before: 12.85 12.33 10.67; after: 17.48 11.88 10.59.

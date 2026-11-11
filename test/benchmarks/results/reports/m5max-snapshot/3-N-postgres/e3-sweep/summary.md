# 3-N-postgres/e3-sweep: E3 (uniform), 3 node(s) on postgres

Peak 6,863.2/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 6,197.0/s at 256 clients, p99 73.7 ms.
Failed more than 1% (not a peak): 4,096 clients: 916.4/s answered, failed 28.6% (11,005 × 504 wow.wait.timeout). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 395.0 | 2.19 | 6.85 | 11,851 | 0 | 0.13 | 0.01 | 0.11 / 0.47 | 7.9 | 35.7 |  |
| 4 | 870.9 | 4.06 | 11.20 | 26,127 | 0 | 0.14 | 0.04 | 0.06 / 0.11 | 6.7 | 13.4 |  |
| 16 | 1,807.8 | 8.26 | 17.54 | 54,233 | 0 | 0.34 | 0.08 | 0.03 / 0.07 | 10.7 | 11.2 |  |
| 64 | 3,510.2 | 17.66 | 37.63 | 105,306 | 0 | 0.64 | 0.11 | 0.06 / 0.09 | 13.5 | 18.0 |  |
| 256 | 6,197.0 | 39.94 | 73.73 | 185,911 | 0 | 0.81 | 0.12 | 0.07 / 0.13 | 14.3 | 24.9 |  |
| 1,024 | 6,863.2 | 117.25 | 446.46 | 205,897 | 0 | 0.90 | 0.13 | 0.02 / 0.02 | 14.7 | 39.1 |  |
| 4,096 | 916.4 | 13.82 | 9175.04 | 27,491 | 11,005 × http-504-wow.wait.timeout | 0.30 | 0.07 | 0.05 / 0.12 | 13.3 | 39.7 |  |

Load averages before: 35.48 21.84 15.77; after: 28.57 24.00 18.63.

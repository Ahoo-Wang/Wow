# 1-S-mongo/e3-sweep: E3 (uniform), 1 node(s) on mongo

Peak 7,341.7/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 4,809.4/s at 256 clients, p99 173.1 ms.
Failed more than 1% (not a peak): 4,096 clients: 4,282.4/s answered, failed 75.7% (397,534 × 429 wow.command.busy, 1,989 × 504 wow.wait.timeout). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 174.3 | 4.51 | 14.14 | 5,230 | 0 | 0.10 | 0.03 | 0.29 / 0.42 | 1.7 | 23.0 |  |
| 4 | 407.3 | 7.93 | 29.31 | 12,218 | 0 | 0.08 | 0.02 | 0.15 / 0.37 | 2.4 | 14.1 |  |
| 16 | 1,330.6 | 9.98 | 32.26 | 39,919 | 0 | 0.19 | 0.03 | 0.62 / 2.06 | 3.2 | 9.6 |  |
| 64 | 2,576.8 | 19.97 | 191.49 | 77,305 | 0 | 0.25 | 0.04 | 0.12 / 0.19 | 4.1 | 8.4 |  |
| 256 | 4,809.4 | 50.43 | 173.06 | 144,282 | 0 | 0.46 | 0.06 | 0.36 / 0.61 | 7.6 | 9.2 |  |
| 1,024 | 7,341.7 | 134.14 | 401.41 | 220,250 | 0 | 0.56 | 0.19 | 0.15 / 0.24 | 10.6 | 10.8 |  |
| 4,096 | 4,282.4 | 485.38 | 5931.01 | 128,473 | 397,534 × http-429-wow.command.busy, 1,989 × http-504-wow.wait.timeout | 1.25 | 0.16 | 0.18 / 0.35 | 12.8 | 16.4 |  |

Load averages before: 26.70 13.96 11.53; after: 19.46 12.96 11.39.

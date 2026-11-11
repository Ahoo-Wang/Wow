# e1-hot-arms/3-head-21/e1-hot-sweep: E1 (hot), 1 node(s) on postgres

Peak 8,928.9/s at 256 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 7,781.9/s at 64 clients, p99 21.2 ms.
Failed more than 1% (not a peak): 4,096 clients: 18,818.7/s answered, failed 47.1% (502,066 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 583.6 | 1.25 | 4.01 | 17,508 | 0 | 0.11 | 0.05 | 0.84 / 0.93 | 2.2 | 14.2 |  |
| 4 | 1,707.7 | 1.87 | 7.46 | 51,232 | 0 | 0.22 | 0.11 | 0.93 / 1.10 | 3.8 | 10.2 |  |
| 16 | 2,297.3 | 5.15 | 22.14 | 68,919 | 0 | 0.30 | 0.09 | 1.29 / 1.58 | 5.3 | 10.9 |  |
| 64 | 7,781.9 | 6.72 | 21.25 | 233,456 | 0 | 0.83 | 0.13 | 1.04 / 1.73 | 6.6 | 12.7 |  |
| 256 | 8,928.9 | 29.31 | 211.97 | 267,867 | 0 | 0.93 | 0.13 | 0.58 / 0.64 | 7.4 | 14.4 |  |
| 1,024 | 8,290.9 | 153.60 | 413.69 | 248,726 | 0 | 0.88 | 0.20 | 0.74 / 1.16 | 9.4 | 15.0 |  |
| 4,096 | 18,818.7 | 98.30 | 1351.68 | 564,561 | 502,066 × http-429-wow.command.busy | 3.21 | 0.39 | 1.00 / 1.37 | 13.6 | 20.6 |  |

Load averages before: 16.70 10.28 9.58; after: 20.57 13.21 10.84.

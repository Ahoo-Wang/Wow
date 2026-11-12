# 1-S-postgres/e3-sweep: E3 (uniform), 1 node(s) on postgres

Peak 7,956.2/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 3,687.4/s at 64 clients, p99 41.7 ms.
Failed more than 1% (not a peak): 4,096 clients: 5,222.8/s answered, failed 69.7% (359,173 × 429 wow.command.busy, 1,066 × 504 wow.wait.timeout). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 396.3 | 2.03 | 10.69 | 11,889 | 0 | 0.13 | 0.06 | 0.61 / 0.72 | 3.2 | 15.8 |  |
| 4 | 422.0 | 4.77 | 30.08 | 12,661 | 0 | 0.08 | 0.02 | 0.36 / 0.47 | 2.4 | 11.1 |  |
| 16 | 2,195.1 | 6.40 | 19.20 | 65,853 | 0 | 0.31 | 0.04 | 0.46 / 0.59 | 4.6 | 9.1 |  |
| 64 | 3,687.4 | 15.55 | 41.73 | 110,622 | 0 | 0.41 | 0.06 | 0.27 / 0.35 | 6.0 | 8.2 |  |
| 256 | 3,650.8 | 57.85 | 704.51 | 109,523 | 0 | 0.35 | 0.04 | 0.34 / 0.39 | 4.7 | 7.7 |  |
| 1,024 | 7,956.2 | 100.86 | 745.47 | 238,685 | 0 | 0.64 | 0.12 | 0.52 / 0.62 | 9.3 | 12.9 |  |
| 4,096 | 5,222.8 | 339.97 | 6684.67 | 156,685 | 359,173 × http-429-wow.command.busy, 1,066 × http-504-wow.wait.timeout | 1.33 | 0.26 | 0.28 / 0.36 | 10.9 | 16.3 |  |

Load averages before: 19.38 10.74 9.62; after: 15.11 10.90 9.77.

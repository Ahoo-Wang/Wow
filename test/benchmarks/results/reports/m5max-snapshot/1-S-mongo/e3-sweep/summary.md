# 1-S-mongo/e3-sweep: E3 (uniform), 1 node(s) on mongo

Peak 7,629.5/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 5,100.5/s at 256 clients, p99 113.2 ms.
Failed more than 1% (not a peak): 4,096 clients: 3,547.0/s answered, failed 74.6% (311,424 × 429 wow.command.busy, 1,493 × 504 wow.wait.timeout). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 216.9 | 4.05 | 10.24 | 6,508 | 0 | 0.11 | 0.01 | 0.19 / 0.49 | 1.9 | 18.0 |  |
| 4 | 527.8 | 6.78 | 16.51 | 15,834 | 0 | 0.09 | 0.02 | 0.21 / 0.39 | 1.8 | 10.7 |  |
| 16 | 1,509.2 | 9.66 | 23.93 | 45,277 | 0 | 0.21 | 0.04 | 0.06 / 0.11 | 3.8 | 7.9 |  |
| 64 | 3,330.3 | 18.18 | 37.89 | 99,909 | 0 | 0.37 | 0.06 | 0.05 / 0.06 | 5.3 | 6.5 |  |
| 256 | 5,100.5 | 48.38 | 113.15 | 153,015 | 0 | 0.51 | 0.07 | 0.10 / 0.21 | 7.6 | 7.5 |  |
| 1,024 | 7,629.5 | 126.97 | 303.10 | 228,885 | 0 | 0.62 | 0.18 | 0.10 / 0.16 | 10.7 | 13.9 |  |
| 4,096 | 3,547.0 | 569.34 | 6651.90 | 106,409 | 311,424 × http-429-wow.command.busy, 1,493 × http-504-wow.wait.timeout | 1.08 | 0.18 | 0.16 / 0.30 | 14.3 | 21.3 |  |

Load averages before: 19.35 10.11 9.09; after: 18.79 11.96 9.85.

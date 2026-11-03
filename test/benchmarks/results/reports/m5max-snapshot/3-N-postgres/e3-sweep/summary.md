# 3-N-postgres/e3-sweep: E3 (uniform), 3 node(s) on postgres

Peak 6,470.6/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 5,770.0/s at 256 clients, p99 78.8 ms.
Failed more than 1% (not a peak): 4,096 clients: 2,665.9/s answered, failed 1.4% (1,123 × 504 wow.wait.timeout, 2 × 409 wow.command.contended). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 378.1 | 2.58 | 4.70 | 11,344 | 0 | 0.16 | 0.07 | 0.09 / 0.23 | 2.6 | 28.2 |  |
| 4 | 952.1 | 4.05 | 7.33 | 28,563 | 0 | 0.16 | 0.07 | 0.08 / 0.11 | 7.1 | 17.1 |  |
| 16 | 1,623.4 | 8.70 | 20.73 | 48,702 | 0 | 0.30 | 0.09 | 0.17 / 0.23 | 10.4 | 21.8 |  |
| 64 | 3,279.9 | 17.92 | 47.62 | 98,398 | 0 | 0.57 | 0.12 | 0.11 / 0.16 | 11.6 | 23.8 |  |
| 256 | 5,770.0 | 43.26 | 78.85 | 173,100 | 0 | 0.81 | 0.12 | 0.23 / 0.56 | 13.6 | 36.0 |  |
| 1,024 | 6,470.6 | 129.53 | 440.32 | 194,117 | 0 | 0.90 | 0.13 | 0.12 / 0.18 | 14.1 | 37.0 |  |
| 4,096 | 2,665.9 | 774.14 | 9306.11 | 79,977 | 1,123 × http-504-wow.wait.timeout, 2 × http-409-wow.command.contended | 0.42 | 0.06 | 0.46 / 1.27 | 13.3 | 33.4 |  |

Load averages before: 31.61 20.80 14.26; after: 29.71 24.74 17.63.

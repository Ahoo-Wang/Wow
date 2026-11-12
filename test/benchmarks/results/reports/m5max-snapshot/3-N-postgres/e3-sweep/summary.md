# 3-N-postgres/e3-sweep: E3 (uniform), 3 node(s) on postgres

Peak 6,950.1/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 6,119.1/s at 256 clients, p99 75.8 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 308.2 | 2.56 | 7.81 | 9,246 | 0 | 0.13 | 0.03 | 0.13 / 0.21 | 2.6 | 26.7 |  |
| 4 | 408.0 | 8.13 | 26.11 | 12,241 | 0 | 0.08 | 0.04 | 0.13 / 0.20 | 3.3 | 14.0 |  |
| 16 | 1,129.4 | 11.33 | 182.27 | 33,883 | 0 | 0.22 | 0.06 | 0.09 / 0.19 | 7.0 | 13.3 |  |
| 64 | 3,214.2 | 18.43 | 45.57 | 96,427 | 0 | 0.58 | 0.11 | 0.12 / 0.16 | 12.2 | 21.4 |  |
| 256 | 6,119.1 | 40.70 | 75.78 | 183,574 | 0 | 0.83 | 0.13 | 0.15 / 0.39 | 14.1 | 27.9 |  |
| 1,024 | 6,950.1 | 119.81 | 415.74 | 208,503 | 0 | 0.90 | 0.14 | 0.10 / 0.13 | 14.4 | 37.6 |  |
| 4,096 | 3,217.4 | 700.41 | 6979.58 | 96,521 | 66 × http-504-wow.wait.timeout, 2 × http-409-wow.command.contended | 0.42 | 0.07 | 0.07 / 0.12 | 14.5 | 43.7 |  |

Load averages before: 29.68 21.74 15.52; after: 44.39 27.88 19.30.

# 3-N-mongo/e3-sweep: E3 (uniform), 3 node(s) on mongo

Peak 5,332.2/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 4,871.1/s at 256 clients, p99 106.0 ms.
Failed more than 1% (not a peak): 4,096 clients: 2,358.7/s answered, failed 4.6% (3,447 × 504 wow.wait.timeout). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 164.9 | 5.86 | 11.33 | 4,948 | 0 | 0.09 | 0.03 | 0.71 / 0.83 | 5.1 | 34.3 |  |
| 4 | 380.5 | 8.45 | 28.80 | 11,415 | 0 | 0.08 | 0.02 | 0.66 / 0.70 | 3.7 | 14.9 |  |
| 16 | 1,464.3 | 10.24 | 20.73 | 43,929 | 0 | 0.24 | 0.05 | 0.28 / 0.44 | 8.6 | 12.8 |  |
| 64 | 2,879.8 | 20.86 | 51.45 | 86,394 | 0 | 0.46 | 0.09 | 0.18 / 0.29 | 11.9 | 17.0 |  |
| 256 | 4,871.1 | 50.17 | 105.98 | 146,132 | 0 | 0.58 | 0.10 | 0.16 / 0.27 | 13.1 | 28.8 |  |
| 1,024 | 5,332.2 | 150.53 | 536.58 | 159,966 | 0 | 0.62 | 0.12 | 0.16 / 0.26 | 14.1 | 38.9 |  |
| 4,096 | 2,358.7 | 321.54 | 9043.97 | 70,760 | 3,447 × http-504-wow.wait.timeout | 0.33 | 0.03 | 0.33 / 0.76 | 11.8 | 43.3 |  |

Load averages before: 34.96 19.37 16.41; after: 39.69 25.86 19.56.

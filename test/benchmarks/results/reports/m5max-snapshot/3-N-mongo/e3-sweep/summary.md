# 3-N-mongo/e3-sweep: E3 (uniform), 3 node(s) on mongo

Peak 5,380.1/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 4,894.1/s at 256 clients, p99 105.0 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 155.0 | 5.92 | 13.31 | 4,651 | 0 | 0.10 | 0.01 | 0.07 / 0.11 | 2.0 | 28.2 |  |
| 4 | 530.2 | 6.40 | 17.54 | 15,905 | 0 | 0.09 | 0.07 | 0.09 / 0.19 | 4.4 | 19.0 |  |
| 16 | 1,150.2 | 11.58 | 56.32 | 34,507 | 0 | 0.19 | 0.05 | 0.06 / 0.11 | 7.0 | 11.8 |  |
| 64 | 2,885.8 | 20.99 | 45.31 | 86,575 | 0 | 0.46 | 0.09 | 0.06 / 0.07 | 11.6 | 17.5 |  |
| 256 | 4,894.1 | 49.41 | 104.96 | 146,824 | 0 | 0.57 | 0.10 | 0.12 / 0.20 | 13.2 | 37.4 |  |
| 1,024 | 5,380.1 | 149.50 | 514.05 | 161,402 | 0 | 0.62 | 0.11 | 0.18 / 0.38 | 14.2 | 37.5 |  |
| 4,096 | 2,495.1 | 1277.95 | 5636.10 | 74,852 | 0 | 0.28 | 0.04 | 0.05 / 0.06 | 11.4 | 30.6 |  |

Load averages before: 35.71 27.35 22.27; after: 26.05 24.81 22.10.

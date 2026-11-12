# 1-S-mongo/e1-zipf-sweep: E1 (zipf), 1 node(s) on mongo

Peak 13,372.5/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 3,353.8/s at 64 clients, p99 197.6 ms.
Failed more than 1% (not a peak): 4,096 clients: 15,452.7/s answered, failed 13.7% (73,748 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 292.0 | 2.93 | 7.42 | 8,761 | 0 | 0.14 | 0.07 | 0.49 / 0.65 | 1.5 | 18.9 |  |
| 4 | 998.9 | 2.70 | 13.95 | 29,967 | 0 | 0.16 | 0.05 | 0.66 / 0.82 | 3.2 | 13.1 |  |
| 16 | 1,640.8 | 5.66 | 96.25 | 49,223 | 0 | 0.20 | 0.04 | 0.35 / 0.46 | 3.1 | 9.3 |  |
| 64 | 3,353.8 | 11.07 | 197.63 | 100,613 | 0 | 0.30 | 0.05 | 0.22 / 0.22 | 3.0 | 9.0 |  |
| 256 | 8,015.4 | 18.94 | 239.62 | 240,461 | 0 | 0.73 | 0.09 | 0.36 / 0.55 | 4.5 | 8.3 |  |
| 1,024 | 13,372.5 | 21.25 | 585.73 | 401,176 | 0 | 1.21 | 0.18 | 0.48 / 0.59 | 8.5 | 13.3 |  |
| 4,096 | 15,452.7 | 66.05 | 2490.37 | 463,582 | 73,748 × http-429-wow.command.busy | 1.50 | 0.21 | 0.22 / 0.23 | 9.9 | 12.3 |  |

Load averages before: 22.00 16.03 12.25; after: 11.46 11.77 11.18.

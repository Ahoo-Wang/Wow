# e1-hot-arms/2-old-25/e1-hot-sweep: E1 (hot), 1 node(s) on postgres

Peak 10,256.5/s at 256 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 10,256.5/s at 256 clients, p99 128.5 ms.
Failed more than 1% (not a peak): 4,096 clients: 18,745.3/s answered, failed 47.6% (511,653 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 569.4 | 1.10 | 5.63 | 17,082 | 0 | 0.13 | 0.04 | 0.86 / 1.04 | 1.9 | 9.5 |  |
| 4 | 1,465.7 | 2.02 | 8.96 | 43,972 | 0 | 0.21 | 0.06 | 0.91 / 1.41 | 3.4 | 8.0 |  |
| 16 | 2,332.3 | 5.66 | 19.07 | 69,968 | 0 | 0.31 | 0.07 | 0.96 / 1.11 | 4.7 | 8.6 |  |
| 64 | 6,460.9 | 8.16 | 30.34 | 193,828 | 0 | 0.65 | 0.09 | 0.80 / 0.83 | 4.4 | 7.6 |  |
| 256 | 10,256.5 | 28.29 | 128.51 | 307,695 | 0 | 1.03 | 0.13 | 0.83 / 1.00 | 5.7 | 7.6 |  |
| 1,024 | 9,179.5 | 140.29 | 339.97 | 275,384 | 0 | 0.93 | 0.15 | 0.77 / 0.84 | 7.1 | 8.6 |  |
| 4,096 | 18,745.3 | 95.74 | 1400.83 | 562,360 | 511,653 × http-429-wow.command.busy | 3.27 | 0.43 | 1.44 / 2.77 | 12.5 | 16.8 |  |

Load averages before: 10.37 6.11 8.80; after: 16.83 9.10 9.16.

# 1-S-postgres/e3-sweep: E3 (uniform), 1 node(s) on postgres

Peak 8,298.6/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 4,333.5/s at 256 clients, p99 93.7 ms.
Failed more than 1% (not a peak): 4,096 clients: 1,117.9/s answered, failed 19.4% (8,064 × 504 wow.wait.timeout, 11 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 218.7 | 2.94 | 11.84 | 6,561 | 0 | 0.13 | 0.02 | 0.88 / 1.00 | 2.8 | 16.9 |  |
| 4 | 641.5 | 4.54 | 17.02 | 19,245 | 0 | 0.11 | 0.04 | 0.80 / 1.03 | 4.4 | 11.2 |  |
| 16 | 1,154.7 | 10.43 | 178.18 | 34,641 | 0 | 0.18 | 0.04 | 1.11 / 1.77 | 3.6 | 14.4 |  |
| 64 | 3,381.6 | 17.41 | 41.98 | 101,449 | 0 | 0.42 | 0.06 | 0.77 / 0.89 | 6.3 | 10.6 |  |
| 256 | 4,333.5 | 62.21 | 93.69 | 130,005 | 0 | 0.42 | 0.05 | 0.78 / 0.91 | 5.6 | 12.4 |  |
| 1,024 | 8,298.6 | 105.98 | 630.78 | 248,957 | 0 | 0.68 | 0.10 | 0.79 / 0.83 | 8.2 | 15.2 |  |
| 4,096 | 1,117.9 | 22.91 | 9502.72 | 33,536 | 8,064 × http-504-wow.wait.timeout, 11 × http-429-wow.command.busy | 0.23 | 0.11 | 0.84 / 0.96 | 7.6 | 12.7 |  |

Load averages before: 19.79 12.27 9.89; after: 9.91 10.79 9.84.

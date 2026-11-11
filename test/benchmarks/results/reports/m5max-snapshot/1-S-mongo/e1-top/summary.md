# 1-S-mongo/e1-top: E1 (uniform), 1 node(s) on mongo

Peak 20,694.2/s at 4,096 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 8,192 clients: 17,632.7/s answered, failed 4.3% (23,949 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 20,694.2 | 191.49 | 434.18 | 620,825 | 0 | 1.52 | 0.22 | 0.75 / 0.79 | 13.1 | 23.5 |  |
| 8,192 | 17,632.7 | 458.75 | 655.36 | 528,982 | 23,949 × http-429-wow.command.busy | 1.95 | 0.17 | 1.04 / 1.23 | 11.9 | 24.8 |  |

Load averages before: 24.67 14.93 11.52; after: 21.06 16.21 12.33.

# 1-S-mongo/e1-top: E1 (uniform), 1 node(s) on mongo

Peak 21,949.7/s at 4,096 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 8,192 clients: 20,601.2/s answered, failed 8.3% (56,003 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 21,949.7 | 180.22 | 376.83 | 658,491 | 0 | 1.74 | 0.25 | 0.10 / 0.11 | 11.6 | 19.7 |  |
| 8,192 | 20,601.2 | 380.93 | 589.82 | 618,036 | 56,003 × http-429-wow.command.busy | 2.14 | 0.16 | 0.10 / 0.14 | 11.0 | 20.4 |  |

Load averages before: 19.50 12.22 10.44; after: 17.26 13.34 11.03.

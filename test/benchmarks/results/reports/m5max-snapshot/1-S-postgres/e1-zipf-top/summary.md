# 1-S-postgres/e1-zipf-top: E1 (zipf), 1 node(s) on postgres

Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 4,096 clients: 20,620.8/s answered, failed 10.7% (74,031 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 20,620.8 | 63.74 | 1703.93 | 618,625 | 74,031 × http-429-wow.command.busy | 1.98 | 0.09 | 0.17 / 0.41 | 3.9 | 13.1 |  |

Load averages before: 11.32 10.73 10.17; after: 13.93 9.99 9.81.

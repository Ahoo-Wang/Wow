# 1-S-postgres/e1-zipf-top: E1 (zipf), 1 node(s) on postgres

Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 4,096 clients: 19,070.2/s answered, failed 13.0% (85,597 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 19,070.2 | 68.61 | 1933.31 | 572,107 | 85,597 × http-429-wow.command.busy | 1.85 | 0.06 | 1.11 / 1.46 | 4.0 | 15.8 |  |

Load averages before: 19.95 15.31 9.71; after: 10.10 11.59 9.16.

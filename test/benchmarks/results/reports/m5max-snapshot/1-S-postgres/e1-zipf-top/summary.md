# 1-S-postgres/e1-zipf-top: E1 (zipf), 1 node(s) on postgres

Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 4,096 clients: 17,714.5/s answered, failed 13.5% (82,969 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 17,714.5 | 63.49 | 1835.01 | 531,435 | 82,969 × http-429-wow.command.busy | 1.84 | 0.10 | 0.27 / 1.42 | 3.7 | 21.4 |  |

Load averages before: 26.29 17.35 11.61; after: 7.54 11.42 10.17.

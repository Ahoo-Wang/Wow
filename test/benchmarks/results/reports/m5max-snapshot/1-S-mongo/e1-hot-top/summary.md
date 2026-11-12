# 1-S-mongo/e1-hot-top: E1 (hot), 1 node(s) on mongo

Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 4,096 clients: 17,971.2/s answered, failed 49.5% (527,698 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 17,971.2 | 158.72 | 4751.36 | 539,136 | 527,698 × http-429-wow.command.busy | 2.81 | 0.46 | 1.06 / 1.40 | 14.0 | 27.6 |  |

Load averages before: 23.15 11.44 10.56; after: 26.70 13.96 11.53.

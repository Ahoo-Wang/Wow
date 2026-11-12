# 1-S-mongo/e1-zipf-top: E1 (zipf), 1 node(s) on mongo

Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 4,096 clients: 17,511.2/s answered, failed 13.1% (78,858 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 17,511.2 | 66.05 | 1859.58 | 525,336 | 78,858 × http-429-wow.command.busy | 1.67 | 0.17 | 0.38 / 0.73 | 7.8 | 11.9 |  |

Load averages before: 11.46 11.77 11.18; after: 12.18 11.67 11.16.

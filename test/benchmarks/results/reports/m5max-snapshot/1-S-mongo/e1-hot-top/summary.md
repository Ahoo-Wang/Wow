# 1-S-mongo/e1-hot-top: E1 (hot), 1 node(s) on mongo

Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 4,096 clients: 16,087.4/s answered, failed 57.7% (656,829 × 429 wow.command.busy, 1,890 × 504 wow.wait.timeout).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 16,087.4 | 164.86 | 452.61 | 482,623 | 656,829 × http-429-wow.command.busy, 1,890 × http-504-wow.wait.timeout | 3.20 | 0.41 | 0.51 / 0.56 | 13.2 | 20.2 |  |

Load averages before: 16.44 8.06 8.35; after: 19.35 10.11 9.09.

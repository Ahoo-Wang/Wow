# 1-S-mongo/e1-zipf-top: E1 (zipf), 1 node(s) on mongo

Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 4,096 clients: 19,552.6/s answered, failed 14.4% (98,291 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 4,096 | 19,552.6 | 56.58 | 1605.63 | 586,578 | 98,291 × http-429-wow.command.busy | 1.65 | 0.06 | 0.16 / 0.62 | 4.1 | – | 14.2 |  |

Load averages before: 16.38 12.65 12.41; after: 12.48 10.43 11.39.

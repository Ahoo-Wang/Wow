# 1-S-postgres/e1-top: E1 (uniform), 1 node(s) on postgres

Peak 40,594.1/s at 4,096 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 40,594.1/s at 4,096 clients, p99 183.3 ms.
Failed more than 1% (not a peak): 8,192 clients: 22,659.7/s answered, failed 2.7% (19,145 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 4,096 | 40,594.1 | 98.81 | 183.29 | 1,217,824 | 0 | 3.23 | 0.45 | 0.11 / 0.14 | 12.0 | postgres 1.91, kafka 0.36 | 19.2 |  |
| 8,192 | 22,659.7 | 358.40 | 454.65 | 679,790 | 19,145 × http-429-wow.command.busy | 2.22 | 0.16 | 0.06 / 0.09 | 8.0 | – | 20.5 |  |

Load averages before: 19.52 20.91 23.44; after: 18.27 20.04 22.85.

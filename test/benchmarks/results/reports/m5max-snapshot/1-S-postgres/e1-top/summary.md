# 1-S-postgres/e1-top: E1 (uniform), 1 node(s) on postgres

Peak 32,303.0/s at 4,096 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 8,192 clients: 20,823.6/s answered, failed 2.1% (13,373 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4,096 | 32,303.0 | 123.39 | 261.12 | 969,090 | 0 | 2.73 | 0.36 | 0.32 / 0.52 | 12.6 | 25.3 |  |
| 8,192 | 20,823.6 | 389.12 | 495.62 | 624,707 | 13,373 × http-429-wow.command.busy | 2.16 | 0.19 | 0.16 / 0.26 | 10.4 | 22.2 |  |

Load averages before: 22.14 13.61 8.24; after: 18.17 14.95 9.30.

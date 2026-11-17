# 1-S-postgres/e3-sweep: E3 (uniform), 1 node(s) on postgres

Peak 8,042.7/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 3,637.9/s at 64 clients, p99 185.3 ms.
Failed more than 1% (not a peak): 4,096 clients: 6,179.5/s answered, failed 71.7% (467,570 × 429 wow.command.busy, 2,338 × 504 wow.wait.timeout). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | 452.3 | 1.82 | 5.50 | 13,570 | 0 | 0.13 | 0.02 | 0.11 / 0.18 | 3.4 | postgres 0.63, kafka 0.30 | 17.2 |  |
| 4 | 662.4 | 3.90 | 16.51 | 19,871 | 0 | 0.10 | 0.03 | 0.11 / 0.18 | 2.8 | postgres 0.65, kafka 0.32 | 10.9 |  |
| 16 | 2,525.0 | 5.73 | 12.16 | 75,751 | 0 | 0.32 | 0.07 | 0.11 / 0.21 | 5.1 | postgres 0.97, kafka 0.35 | 8.2 |  |
| 64 | 3,637.9 | 13.31 | 185.34 | 109,138 | 0 | 0.41 | 0.05 | 0.15 / 0.31 | 3.9 | postgres 0.98, kafka 0.35 | 9.1 |  |
| 256 | 7,067.2 | 27.14 | 643.07 | 212,017 | 0 | 0.70 | 0.10 | 0.10 / 0.21 | 6.0 | postgres 1.36, kafka 0.35 | 9.5 |  |
| 1,024 | 8,042.7 | 88.06 | 737.28 | 241,280 | 0 | 0.67 | 0.10 | 0.15 / 0.25 | 6.0 | postgres 1.47, kafka 0.35 | 8.6 |  |
| 4,096 | 6,179.5 | 276.48 | 3555.33 | 185,384 | 467,570 × http-429-wow.command.busy, 2,338 × http-504-wow.wait.timeout | 1.78 | 0.20 | 0.11 / 0.14 | 7.9 | – | 16.1 |  |

Load averages before: 19.69 12.06 13.84; after: 13.79 11.02 12.61.

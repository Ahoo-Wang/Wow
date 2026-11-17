# 1-S-postgres/e1-zipf-sweep: E1 (zipf), 1 node(s) on postgres

Peak 19,746.8/s at 256 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 19,746.8/s at 256 clients, p99 29.6 ms.
Failed more than 1% (not a peak): 4,096 clients: 25,193.8/s answered, failed 14.4% (126,957 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | 791.3 | 0.85 | 3.50 | 23,740 | 0 | 0.14 | 0.04 | 0.09 / 0.12 | 1.6 | kafka 0.26, postgres 0.20 | 15.0 |  |
| 4 | 1,650.6 | 1.67 | 7.52 | 49,518 | 0 | 0.20 | 0.08 | 0.19 / 0.29 | 2.1 | postgres 0.44, kafka 0.31 | 9.5 |  |
| 16 | 4,712.8 | 2.53 | 10.49 | 141,385 | 0 | 0.50 | 0.12 | 0.23 / 0.45 | 6.3 | postgres 0.75, kafka 0.35 | 7.1 |  |
| 64 | 9,838.0 | 5.21 | 17.79 | 295,140 | 0 | 0.93 | 0.12 | 0.11 / 0.21 | 5.2 | postgres 1.07, kafka 0.36 | 12.1 |  |
| 256 | 19,746.8 | 11.58 | 29.57 | 592,404 | 0 | 1.81 | 0.25 | 0.13 / 0.20 | 10.2 | postgres 1.79, kafka 0.40 | 17.7 |  |
| 1,024 | 19,335.5 | 14.02 | 337.92 | 580,064 | 0 | 1.76 | 0.24 | 0.10 / 0.13 | 10.8 | postgres 2.14, kafka 0.41 | 18.6 |  |
| 4,096 | 25,193.8 | 41.73 | 1425.41 | 755,815 | 126,957 × http-429-wow.command.busy | 2.35 | 0.29 | 0.38 / 0.98 | 13.7 | – | 20.2 |  |

Load averages before: 18.27 20.04 22.85; after: 19.72 16.95 20.27.

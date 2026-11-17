# 3-N-postgres/e3-sweep: E3 (uniform), 3 node(s) on postgres

Peak 8,623.0/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 7,279.0/s at 256 clients, p99 59.6 ms.
Failed more than 1% (not a peak): 4,096 clients: 7,339.6/s answered, failed 8.9% (18,424 × 429 wow.command.busy, 3,014 × 504 wow.wait.timeout). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | 456.2 | 2.11 | 4.22 | 13,685 | 0 | 0.14 | 0.02 | 0.12 / 0.18 | 3.0 | postgres 0.42, kafka 0.33 | 33.3 |  |
| 4 | 559.0 | 6.27 | 18.56 | 16,770 | 0 | 0.10 | 0.03 | 0.07 / 0.10 | 4.1 | postgres 0.63, kafka 0.34 | 21.1 |  |
| 16 | 1,451.7 | 9.73 | 27.01 | 43,551 | 0 | 0.27 | 0.07 | 0.11 / 0.14 | 7.3 | postgres 1.15, kafka 0.42 | 19.3 |  |
| 64 | 3,881.5 | 15.81 | 29.18 | 116,444 | 0 | 0.68 | 0.15 | 0.12 / 0.25 | 13.0 | postgres 1.62, kafka 0.51 | 19.6 |  |
| 256 | 7,279.0 | 34.05 | 59.65 | 218,370 | 0 | 0.97 | 0.14 | 0.23 / 0.40 | 13.7 | postgres 1.75, kafka 0.54 | 33.9 |  |
| 1,024 | 8,623.0 | 102.91 | 309.25 | 258,690 | 0 | 1.11 | 0.16 | 0.24 / 0.42 | 15.4 | postgres 1.86, kafka 0.53 | 39.7 |  |
| 4,096 | 7,339.6 | 136.19 | 6619.14 | 220,187 | 18,424 × http-429-wow.command.busy, 3,014 × http-504-wow.wait.timeout | 1.04 | 0.11 | 0.11 / 0.23 | 11.2 | – | 48.5 |  |

Load averages before: 41.65 24.68 16.72; after: 40.78 29.43 20.46.

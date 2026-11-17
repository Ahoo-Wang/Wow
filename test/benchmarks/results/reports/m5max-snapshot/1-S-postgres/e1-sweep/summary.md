# 1-S-postgres/e1-sweep: E1 (uniform), 1 node(s) on postgres

Peak ≥ 41,706.1/s at 4,096 clients (its last step: a lower bound).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 41,706.1/s at 4,096 clients, p99 167.9 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | 1,298.7 | 0.75 | 1.22 | 38,960 | 0 | 0.21 | 0.08 | 0.18 / 0.29 | 2.1 | kafka 0.26, postgres 0.25 | 18.9 |  |
| 4 | 2,353.2 | 1.55 | 5.50 | 70,596 | 0 | 0.25 | 0.08 | 0.13 / 0.22 | 3.5 | postgres 0.55, kafka 0.32 | 11.9 |  |
| 16 | 4,174.5 | 2.56 | 10.62 | 125,236 | 0 | 0.44 | 0.12 | 0.08 / 0.12 | 4.9 | postgres 0.62, kafka 0.31 | 8.1 |  |
| 64 | 14,623.2 | 4.05 | 8.83 | 438,696 | 0 | 1.31 | 0.19 | 0.07 / 0.13 | 7.3 | postgres 1.11, kafka 0.41 | 9.8 |  |
| 256 | 23,754.2 | 9.92 | 20.99 | 712,625 | 0 | 2.11 | 0.26 | 0.19 / 0.32 | 10.1 | postgres 1.39, kafka 0.42 | 11.5 |  |
| 1,024 | 41,605.7 | 24.06 | 46.08 | 1,248,170 | 0 | 3.44 | 0.46 | 0.05 / 0.06 | 13.0 | postgres 1.74, kafka 0.40 | 17.8 |  |
| 4,096 | 41,706.1 | 96.77 | 167.94 | 1,251,182 | 0 | 3.28 | 0.45 | 0.11 / 0.23 | 12.0 | – | 22.7 |  |

Load averages before: 23.61 30.73 27.44; after: 19.52 20.91 23.44.

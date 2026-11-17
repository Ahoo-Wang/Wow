# 3-N-postgres/e1-sweep: E1 (uniform), 3 node(s) on postgres

Peak 25,104.3/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 25,104.3/s at 1,024 clients, p99 90.6 ms.
Failed more than 1% (not a peak): 4,096 clients: 25,482.8/s answered, failed 8.5% (71,059 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | 800.8 | 1.03 | 3.46 | 24,024 | 0 | 0.16 | 0.06 | 0.22 / 0.53 | 2.1 | kafka 0.29, postgres 0.24 | 17.6 |  |
| 4 | 2,482.7 | 1.54 | 3.71 | 74,480 | 0 | 0.30 | 0.09 | 0.09 / 0.10 | 4.5 | postgres 0.73, kafka 0.38 | 11.9 |  |
| 16 | 4,980.2 | 3.04 | 6.59 | 149,406 | 0 | 0.77 | 0.23 | 0.14 / 0.35 | 10.2 | postgres 1.48, kafka 0.48 | 13.5 |  |
| 64 | 8,021.6 | 6.59 | 20.22 | 240,649 | 0 | 0.94 | 0.16 | 0.10 / 0.13 | 10.1 | postgres 1.29, kafka 0.45 | 20.9 |  |
| 256 | 18,559.3 | 13.44 | 23.81 | 556,778 | 0 | 1.73 | 0.24 | 0.05 / 0.06 | 14.1 | postgres 1.50, kafka 0.51 | 35.4 |  |
| 1,024 | 25,104.3 | 31.87 | 90.62 | 753,130 | 0 | 2.16 | 0.30 | 0.22 / 0.46 | 15.6 | postgres 1.50, kafka 0.50 | 40.0 |  |
| 4,096 | 25,482.8 | 79.36 | 444.42 | 764,484 | 71,059 × http-429-wow.command.busy | 2.42 | 0.25 | 0.11 / 0.17 | 14.0 | – | 40.5 |  |

Load averages before: 21.12 14.20 12.05; after: 41.65 24.68 16.72.

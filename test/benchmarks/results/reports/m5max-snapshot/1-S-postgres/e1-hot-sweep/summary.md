# 1-S-postgres/e1-hot-sweep: E1 (hot), 1 node(s) on postgres

Peak 11,853.7/s at 256 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 11,853.7/s at 256 clients, p99 76.8 ms.
Failed more than 1% (not a peak): 4,096 clients: 21,540.2/s answered, failed 45.3% (535,534 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | 1,027.3 | 0.77 | 3.13 | 30,820 | 0 | 0.13 | 0.02 | 0.16 / 0.36 | 1.7 | kafka 0.27, postgres 0.27 | 10.3 |  |
| 4 | 2,008.8 | 1.69 | 6.94 | 60,263 | 0 | 0.23 | 0.06 | 1.40 / 2.81 | 4.0 | postgres 0.66, kafka 0.34 | 4.6 |  |
| 16 | 4,678.7 | 2.91 | 10.30 | 140,362 | 0 | 0.51 | 0.09 | 0.53 / 0.91 | 4.5 | postgres 0.85, kafka 0.32 | 6.6 |  |
| 64 | 10,806.2 | 5.44 | 13.76 | 324,187 | 0 | 1.08 | 0.16 | 0.05 / 0.07 | 7.6 | postgres 1.41, kafka 0.38 | 8.8 |  |
| 256 | 11,853.7 | 23.55 | 76.80 | 355,611 | 0 | 1.19 | 0.18 | 0.10 / 0.15 | 8.5 | postgres 1.54, kafka 0.37 | 10.2 |  |
| 1,024 | 11,761.3 | 125.44 | 296.96 | 352,839 | 0 | 1.14 | 0.16 | 0.12 / 0.31 | 7.5 | postgres 1.51, kafka 0.38 | 11.5 |  |
| 4,096 | 21,540.2 | 85.50 | 651.26 | 646,207 | 535,534 × http-429-wow.command.busy | 3.33 | 0.39 | 0.10 / 0.12 | 12.1 | – | 16.6 |  |

Load averages before: 12.46 11.94 17.13; after: 16.88 10.43 13.40.

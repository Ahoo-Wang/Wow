# 1-S-mongo/e1-zipf-sweep: E1 (zipf), 1 node(s) on mongo

Peak 3,898.2/s at 256 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 2,819.9/s at 64 clients, p99 123.4 ms.
Failed more than 1% (not a peak): 4,096 clients: 6,667.6/s answered, failed 13.3% (30,749 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 325.8 | 2.91 | 6.53 | 9,773 | 0 | 0.13 | 0.01 | 0.11 / 0.19 | 1.4 | 14.8 |  |
| 4 | 1,038.1 | 2.99 | 10.11 | 31,143 | 0 | 0.13 | 0.03 | 0.05 / 0.06 | 1.7 | 8.7 |  |
| 16 | 1,758.2 | 6.37 | 36.61 | 52,745 | 0 | 0.22 | 0.11 | 0.16 / 0.30 | 2.8 | 6.5 |  |
| 64 | 2,819.9 | 14.46 | 123.39 | 84,596 | 0 | 0.29 | 0.04 | 0.09 / 0.14 | 2.5 | 6.2 |  |
| 256 | 3,898.2 | 45.05 | 296.96 | 116,945 | 0 | 0.39 | 0.04 | 0.07 / 0.11 | 3.4 | 5.7 |  |
| 1,024 | 2,672.6 | 53.25 | 3096.57 | 80,179 | 0 | 0.28 | 0.04 | 0.12 / 0.19 | 2.7 | 6.2 |  |
| 4,096 | 6,667.6 | 167.94 | 5406.72 | 200,029 | 30,749 × http-429-wow.command.busy | 0.74 | 0.09 | 0.13 / 0.24 | 4.0 | 4.7 |  |

Load averages before: 17.26 13.34 11.03; after: 3.90 7.46 8.94.

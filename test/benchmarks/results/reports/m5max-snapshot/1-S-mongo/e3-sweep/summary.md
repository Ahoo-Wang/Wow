# 1-S-mongo/e3-sweep: E3 (uniform), 1 node(s) on mongo

Peak 7,738.2/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 5,898.6/s at 256 clients, p99 72.2 ms.
Failed more than 1% (not a peak): 4,096 clients: 3,812.2/s answered, failed 78.8% (423,223 × 429 wow.command.busy, 2,707 × 504 wow.wait.timeout). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | 248.8 | 3.38 | 10.43 | 7,464 | 0 | 0.13 | 0.12 | 0.11 / 0.18 | 4.2 | mongo 1.14, kafka 0.27 | 19.2 |  |
| 4 | 651.3 | 5.66 | 12.80 | 19,538 | 0 | 0.12 | 0.04 | 0.13 / 0.25 | 2.6 | mongo 0.87, kafka 0.27 | 13.0 |  |
| 16 | 1,531.2 | 9.66 | 21.76 | 45,935 | 0 | 0.20 | 0.05 | 0.10 / 0.14 | 4.5 | mongo 1.20, kafka 0.26 | 10.0 |  |
| 64 | 3,281.8 | 18.05 | 43.77 | 98,454 | 0 | 0.33 | 0.05 | 0.15 / 0.19 | 4.8 | mongo 2.05, kafka 0.31 | 11.2 |  |
| 256 | 5,898.6 | 41.73 | 72.19 | 176,959 | 0 | 0.59 | 0.09 | 0.18 / 0.27 | 8.2 | mongo 3.78, kafka 0.35 | 13.1 |  |
| 1,024 | 7,738.2 | 124.42 | 440.32 | 232,146 | 0 | 0.60 | 0.09 | 0.06 / 0.08 | 9.4 | mongo 4.60, kafka 0.38 | 14.6 |  |
| 4,096 | 3,812.2 | 634.88 | 5734.40 | 114,366 | 423,223 × http-429-wow.command.busy, 2,707 × http-504-wow.wait.timeout | 1.22 | 0.11 | 0.14 / 0.27 | 12.5 | – | 17.6 |  |

MongoDB over each step's window (serverStatus, every second): the journal's syncs and their mean time, the write concern's majority waits and their mean time, the writes' and transactions' mean latency, the transactions aborted, the retried commands, the write conflicts, and the write tickets in use.

| Clients | Journal syncs/s | Sync (ms) | Majority waits/s | Wait (ms) | Write (µs) | Transaction (µs) | Aborted/s | Retried/s | Conflicts/s | Tickets (mean / max) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 754.6 | 0.61 | 1,326.0 | 0.00 | 1,552 | – | 0.00 | 0.00 | 0.00 | 0.1 / 1 |
| 4 | 916.3 | 0.58 | 2,099.0 | 0.00 | 1,554 | – | 0.00 | 0.00 | 0.00 | 0.3 / 2 |
| 16 | 735.8 | 0.88 | 1,522.2 | 0.00 | 2,100 | – | 0.00 | 0.00 | 0.00 | 0.3 / 2 |
| 64 | 565.4 | 1.10 | 1,052.4 | 0.00 | 3,069 | – | 0.00 | 0.00 | 0.00 | 0.6 / 3 |
| 256 | 403.7 | 1.37 | 611.0 | 0.00 | 5,376 | – | 0.00 | 0.00 | 0.00 | 1.2 / 3 |
| 1,024 | 259.1 | 1.63 | 336.7 | 0.01 | 12,194 | – | 0.00 | 0.00 | 0.00 | 2.3 / 6 |
| 4,096 | 107.7 | 3.53 | 147.2 | 0.02 | 22,511 | – | 0.00 | 0.00 | 0.00 | 2.0 / 6 |

Load averages before: 20.12 11.87 11.03; after: 16.56 12.73 11.45.

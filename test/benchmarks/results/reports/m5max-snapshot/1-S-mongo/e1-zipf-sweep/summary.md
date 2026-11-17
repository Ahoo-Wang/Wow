# 1-S-mongo/e1-zipf-sweep: E1 (zipf), 1 node(s) on mongo

Peak 15,957.5/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 14,914.4/s at 256 clients, p99 42.2 ms.
Failed more than 1% (not a peak): 4,096 clients: 20,449.9/s answered, failed 14.4% (102,934 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | 493.9 | 1.46 | 5.44 | 14,816 | 0 | 0.15 | 0.04 | 0.11 / 0.17 | 1.9 | mongo 0.41, kafka 0.24 | 16.5 |  |
| 4 | 1,546.8 | 2.08 | 6.75 | 46,405 | 0 | 0.24 | 0.05 | 0.16 / 0.25 | 2.3 | mongo 0.67, kafka 0.25 | 9.6 |  |
| 16 | 3,753.1 | 3.62 | 11.07 | 112,594 | 0 | 0.40 | 0.07 | 0.10 / 0.17 | 3.5 | mongo 0.88, kafka 0.28 | 9.0 |  |
| 64 | 7,776.6 | 7.07 | 19.84 | 233,298 | 0 | 0.63 | 0.09 | 0.13 / 0.18 | 4.0 | mongo 1.26, kafka 0.31 | 9.1 |  |
| 256 | 14,914.4 | 15.17 | 42.24 | 447,431 | 0 | 1.32 | 0.17 | 0.08 / 0.14 | 7.8 | mongo 2.30, kafka 0.35 | 11.2 |  |
| 1,024 | 15,957.5 | 17.92 | 397.31 | 478,726 | 0 | 1.42 | 0.18 | 0.11 / 0.13 | 8.6 | mongo 2.99, kafka 0.34 | 11.2 |  |
| 4,096 | 20,449.9 | 52.99 | 1507.33 | 613,497 | 102,934 × http-429-wow.command.busy | 1.78 | 0.26 | 0.10 / 0.13 | 13.3 | – | 14.5 |  |

MongoDB over each step's window (serverStatus, every second): the journal's syncs and their mean time, the write concern's majority waits and their mean time, the writes' and transactions' mean latency, the transactions aborted, the retried commands, the write conflicts, and the write tickets in use.

| Clients | Journal syncs/s | Sync (ms) | Majority waits/s | Wait (ms) | Write (µs) | Transaction (µs) | Aborted/s | Retried/s | Conflicts/s | Tickets (mean / max) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 764.2 | 0.52 | 911.6 | 0.00 | 1,395 | – | 0.00 | 0.00 | 0.00 | 0.1 / 1 |
| 4 | 1,052.6 | 0.49 | 2,303.1 | 0.00 | 1,417 | – | 0.00 | 0.00 | 0.00 | 0.5 / 3 |
| 16 | 859.4 | 0.66 | 1,877.3 | 0.00 | 1,757 | – | 0.00 | 0.00 | 0.00 | 0.3 / 2 |
| 64 | 653.8 | 1.00 | 1,325.1 | 0.00 | 2,521 | – | 0.00 | 0.00 | 0.00 | 0.6 / 3 |
| 256 | 518.3 | 1.14 | 948.4 | 0.00 | 3,451 | – | 0.00 | 0.00 | 0.00 | 1.0 / 3 |
| 1,024 | 506.0 | 1.20 | 886.2 | 0.00 | 3,751 | – | 0.00 | 0.00 | 0.00 | 1.0 / 3 |
| 4,096 | 280.9 | 2.16 | 390.4 | 0.01 | 8,655 | – | 0.00 | 0.00 | 0.00 | 1.4 / 5 |

Load averages before: 19.50 14.33 13.12; after: 16.38 12.65 12.41.

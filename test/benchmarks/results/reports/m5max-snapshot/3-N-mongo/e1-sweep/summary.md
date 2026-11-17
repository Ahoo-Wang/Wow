# 3-N-mongo/e1-sweep: E1 (uniform), 3 node(s) on mongo

Peak 19,052.3/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 19,052.3/s at 1,024 clients, p99 131.1 ms.
Failed more than 1% (not a peak): 4,096 clients: 20,172.6/s answered, failed 8.8% (58,464 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | 355.4 | 2.42 | 5.73 | 10,663 | 0 | 0.12 | 0.02 | 0.10 / 0.16 | 1.7 | mongo 0.38, kafka 0.27 | 23.2 |  |
| 4 | 1,280.2 | 2.45 | 7.23 | 38,406 | 0 | 0.17 | 0.05 | 0.07 / 0.12 | 3.3 | mongo 0.84, kafka 0.32 | 14.3 |  |
| 16 | 3,210.8 | 4.16 | 11.90 | 96,324 | 0 | 0.44 | 0.10 | 0.13 / 0.18 | 7.0 | mongo 1.53, kafka 0.42 | 13.8 |  |
| 64 | 8,462.8 | 6.91 | 15.36 | 253,885 | 0 | 0.93 | 0.14 | 0.14 / 0.15 | 11.1 | mongo 2.35, kafka 0.45 | 12.6 |  |
| 256 | 14,889.5 | 16.64 | 31.87 | 446,685 | 0 | 1.28 | 0.20 | 0.09 / 0.11 | 13.1 | mongo 3.50, kafka 0.46 | 25.2 |  |
| 1,024 | 19,052.3 | 40.96 | 131.07 | 571,568 | 0 | 1.45 | 0.24 | 0.43 / 0.94 | 14.2 | mongo 4.52, kafka 0.48 | 33.7 |  |
| 4,096 | 20,172.6 | 105.98 | 589.82 | 605,178 | 58,464 × http-429-wow.command.busy | 1.78 | 0.25 | 0.10 / 0.14 | 14.9 | – | 43.6 |  |

MongoDB over each step's window (serverStatus, every second): the journal's syncs and their mean time, the write concern's majority waits and their mean time, the writes' and transactions' mean latency, the transactions aborted, the retried commands, the write conflicts, and the write tickets in use.

| Clients | Journal syncs/s | Sync (ms) | Majority waits/s | Wait (ms) | Write (µs) | Transaction (µs) | Aborted/s | Retried/s | Conflicts/s | Tickets (mean / max) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 568.2 | 0.80 | 720.7 | 0.00 | 1,844 | – | 0.00 | 0.00 | 0.00 | 0.1 / 1 |
| 4 | 752.3 | 0.80 | 2,559.8 | 0.00 | 1,961 | – | 0.00 | 0.00 | 0.00 | 0.2 / 2 |
| 16 | 757.3 | 1.06 | 3,831.5 | 0.00 | 2,467 | – | 0.00 | 0.00 | 0.00 | 0.5 / 3 |
| 64 | 711.7 | 1.14 | 3,560.4 | 0.00 | 2,650 | – | 0.00 | 0.00 | 0.00 | 1.3 / 5 |
| 256 | 423.2 | 2.06 | 2,076.3 | 0.00 | 4,771 | – | 0.00 | 0.00 | 0.00 | 1.6 / 5 |
| 1,024 | 243.9 | 3.68 | 1,172.7 | 0.01 | 8,641 | – | 0.00 | 0.00 | 0.00 | 2.2 / 7 |
| 4,096 | 216.8 | 3.99 | 1,024.3 | 0.01 | 9,550 | – | 0.00 | 0.00 | 0.00 | 2.3 / 5 |

Load averages before: 28.44 33.15 24.61; after: 45.22 31.06 25.13.

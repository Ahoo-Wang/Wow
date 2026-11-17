# 1-S-mongo/e1-sweep: E1 (uniform), 1 node(s) on mongo

Peak ≥ 28,609.4/s at 4,096 clients (its last step: a lower bound).
Sustainable (the highest step with a p99 of 200 ms or less and no error): 28,196.1/s at 1,024 clients, p99 66.6 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | 434.6 | 1.75 | 5.63 | 13,038 | 0 | 0.16 | 0.07 | 0.14 / 0.29 | 1.2 | mongo 0.35, kafka 0.25 | 12.1 |  |
| 4 | 1,575.3 | 1.98 | 6.78 | 47,259 | 0 | 0.23 | 0.06 | 0.11 / 0.20 | 2.6 | mongo 0.58, kafka 0.25 | 7.2 |  |
| 16 | 3,059.3 | 4.29 | 13.63 | 91,778 | 0 | 0.33 | 0.06 | 0.12 / 0.20 | 3.0 | mongo 0.66, kafka 0.27 | 6.3 |  |
| 64 | 8,699.1 | 6.62 | 16.64 | 260,972 | 0 | 0.66 | 0.10 | 0.10 / 0.19 | 4.7 | mongo 1.29, kafka 0.30 | 7.8 |  |
| 256 | 16,583.7 | 14.85 | 28.54 | 497,510 | 0 | 1.43 | 0.18 | 0.05 / 0.07 | 8.0 | mongo 2.88, kafka 0.36 | 9.7 |  |
| 1,024 | 28,196.1 | 35.33 | 66.56 | 845,883 | 0 | 2.14 | 0.30 | 0.08 / 0.16 | 11.8 | mongo 4.70, kafka 0.39 | 15.4 |  |
| 4,096 | 28,609.4 | 135.17 | 325.63 | 858,281 | 0 | 2.04 | 0.34 | 0.07 / 0.08 | 13.1 | – | 16.3 |  |

MongoDB over each step's window (serverStatus, every second): the journal's syncs and their mean time, the write concern's majority waits and their mean time, the writes' and transactions' mean latency, the transactions aborted, the retried commands, the write conflicts, and the write tickets in use.

| Clients | Journal syncs/s | Sync (ms) | Majority waits/s | Wait (ms) | Write (µs) | Transaction (µs) | Aborted/s | Retried/s | Conflicts/s | Tickets (mean / max) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 646.0 | 0.76 | 774.3 | 0.00 | 1,717 | – | 0.00 | 0.00 | 0.00 | 0.1 / 1 |
| 4 | 1,041.5 | 0.49 | 2,274.2 | 0.00 | 1,398 | – | 0.00 | 0.00 | 0.00 | 0.2 / 1 |
| 16 | 640.2 | 1.23 | 1,320.9 | 0.00 | 2,688 | – | 0.00 | 0.00 | 0.00 | 0.1 / 2 |
| 64 | 674.2 | 0.93 | 1,327.9 | 0.00 | 2,445 | – | 0.00 | 0.00 | 0.00 | 0.4 / 3 |
| 256 | 522.6 | 1.12 | 848.7 | 0.00 | 3,935 | – | 0.00 | 0.00 | 0.00 | 1.2 / 4 |
| 1,024 | 350.2 | 1.63 | 474.5 | 0.00 | 8,820 | – | 0.00 | 0.00 | 0.00 | 2.2 / 5 |
| 4,096 | 219.1 | 2.32 | 296.7 | 0.00 | 15,424 | – | 0.00 | 0.00 | 0.00 | 2.7 / 6 |

Load averages before: 13.20 11.23 12.59; after: 16.69 11.81 12.16.

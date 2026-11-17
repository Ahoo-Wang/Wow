# 3-N-mongo/e3-sweep: E3 (uniform), 3 node(s) on mongo

Peak 6,060.2/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 5,462.9/s at 256 clients, p99 78.8 ms.
Failed more than 1% (not a peak): 4,096 clients: 4,936.9/s answered, failed 11.9% (16,439 × 429 wow.command.busy, 3,522 × 504 wow.wait.timeout). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | 201.4 | 4.67 | 9.66 | 6,041 | 0 | 0.11 | 0.07 | 0.14 / 0.20 | 2.8 | mongo 0.56, kafka 0.28 | 36.2 |  |
| 4 | 695.5 | 5.41 | 11.39 | 20,866 | 0 | 0.11 | 0.03 | 0.28 / 0.40 | 5.1 | mongo 1.41, kafka 0.37 | 22.1 |  |
| 16 | 1,593.9 | 9.54 | 18.82 | 47,818 | 0 | 0.25 | 0.07 | 0.25 / 0.36 | 9.3 | mongo 2.33, kafka 0.43 | 16.8 |  |
| 64 | 2,926.2 | 20.09 | 74.75 | 87,785 | 0 | 0.45 | 0.09 | 0.12 / 0.19 | 9.8 | mongo 2.93, kafka 0.47 | 17.1 |  |
| 256 | 5,462.9 | 45.82 | 78.85 | 163,888 | 0 | 0.63 | 0.11 | 0.41 / 0.55 | 14.2 | mongo 4.79, kafka 0.52 | 32.8 |  |
| 1,024 | 6,060.2 | 143.36 | 471.04 | 181,807 | 0 | 0.67 | 0.10 | 0.58 / 0.63 | 14.4 | mongo 4.75, kafka 0.50 | 36.6 |  |
| 4,096 | 4,936.9 | 278.53 | 6815.74 | 148,108 | 16,439 × http-429-wow.command.busy, 3,522 × http-504-wow.wait.timeout | 0.64 | 0.07 | 0.48 / 0.64 | 11.7 | – | 35.2 |  |

MongoDB over each step's window (serverStatus, every second): the journal's syncs and their mean time, the write concern's majority waits and their mean time, the writes' and transactions' mean latency, the transactions aborted, the retried commands, the write conflicts, and the write tickets in use.

| Clients | Journal syncs/s | Sync (ms) | Majority waits/s | Wait (ms) | Write (µs) | Transaction (µs) | Aborted/s | Retried/s | Conflicts/s | Tickets (mean / max) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 627.1 | 0.68 | 1,034.8 | 0.00 | 1,800 | – | 0.00 | 0.00 | 0.00 | 0.1 / 2 |
| 4 | 953.8 | 0.65 | 3,825.2 | 0.00 | 1,642 | – | 0.00 | 0.00 | 0.00 | 0.5 / 7 |
| 16 | 860.2 | 0.88 | 4,391.3 | 0.00 | 2,052 | – | 0.00 | 0.00 | 0.00 | 1.0 / 5 |
| 64 | 561.4 | 1.51 | 2,705.6 | 0.00 | 3,586 | – | 0.00 | 0.00 | 0.00 | 1.6 / 4 |
| 256 | 331.6 | 2.66 | 1,527.1 | 0.01 | 6,413 | – | 0.00 | 0.00 | 0.00 | 2.2 / 6 |
| 1,024 | 239.4 | 3.56 | 1,091.8 | 0.01 | 9,193 | – | 0.00 | 0.00 | 0.00 | 2.6 / 9 |
| 4,096 | 211.3 | 3.48 | 855.8 | 0.01 | 10,261 | – | 0.00 | 0.00 | 0.00 | 2.7 / 8 |

Load averages before: 45.22 31.06 25.13; after: 30.90 27.25 24.50.

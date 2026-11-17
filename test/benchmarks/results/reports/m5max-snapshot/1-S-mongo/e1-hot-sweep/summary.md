# 1-S-mongo/e1-hot-sweep: E1 (hot), 1 node(s) on mongo

Peak 9,948.1/s at 256 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 9,948.1/s at 256 clients, p99 78.8 ms.
Failed more than 1% (not a peak): 4,096 clients: 20,048.4/s answered, failed 47.9% (552,724 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | 369.4 | 2.19 | 5.98 | 11,081 | 0 | 0.13 | 0.02 | 0.16 / 0.25 | 1.6 | mongo 0.56, kafka 0.25 | 10.8 |  |
| 4 | 1,391.9 | 2.37 | 7.49 | 41,756 | 0 | 0.22 | 0.05 | 0.07 / 0.14 | 2.8 | mongo 0.77, kafka 0.27 | 3.8 |  |
| 16 | 3,740.6 | 3.74 | 10.75 | 112,217 | 0 | 0.41 | 0.07 | 0.19 / 0.37 | 4.0 | mongo 0.96, kafka 0.26 | 3.8 |  |
| 64 | 8,013.7 | 7.07 | 18.69 | 240,410 | 0 | 0.66 | 0.09 | 0.14 / 0.36 | 4.8 | mongo 1.45, kafka 0.31 | 6.8 |  |
| 256 | 9,948.1 | 28.80 | 78.85 | 298,443 | 0 | 0.89 | 0.14 | 0.07 / 0.11 | 6.4 | mongo 2.04, kafka 0.31 | 8.5 |  |
| 1,024 | 9,588.9 | 155.65 | 309.25 | 287,668 | 0 | 0.86 | 0.12 | 0.11 / 0.29 | 6.5 | mongo 2.04, kafka 0.34 | 9.1 |  |
| 4,096 | 20,048.4 | 96.25 | 1392.64 | 601,453 | 552,724 × http-429-wow.command.busy | 3.09 | 0.38 | 0.12 / 0.15 | 12.5 | – | 16.4 |  |

MongoDB over each step's window (serverStatus, every second): the journal's syncs and their mean time, the write concern's majority waits and their mean time, the writes' and transactions' mean latency, the transactions aborted, the retried commands, the write conflicts, and the write tickets in use.

| Clients | Journal syncs/s | Sync (ms) | Majority waits/s | Wait (ms) | Write (µs) | Transaction (µs) | Aborted/s | Retried/s | Conflicts/s | Tickets (mean / max) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 199.3 | 0.88 | 246.6 | 0.00 | 1,911 | – | 0.00 | 0.00 | 0.00 | 0.0 / 1 |
| 4 | 895.6 | 0.60 | 1,889.1 | 0.00 | 1,597 | – | 0.00 | 0.00 | 0.00 | 0.3 / 2 |
| 16 | 862.7 | 0.60 | 1,946.5 | 0.00 | 1,665 | – | 0.00 | 0.00 | 0.00 | 0.3 / 4 |
| 64 | 681.8 | 0.91 | 1,417.6 | 0.00 | 2,330 | – | 0.00 | 0.00 | 0.00 | 0.6 / 2 |
| 256 | 640.5 | 0.94 | 1,296.3 | 0.00 | 2,514 | – | 0.00 | 0.00 | 0.00 | 0.5 / 3 |
| 1,024 | 615.0 | 1.04 | 1,237.0 | 0.00 | 2,697 | – | 0.00 | 0.00 | 0.00 | 0.5 / 2 |

Load averages before: 12.48 10.43 11.39; after: 16.95 10.12 10.41.

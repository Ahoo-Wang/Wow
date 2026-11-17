# 1-S-mongo/e1-top: E1 (uniform), 1 node(s) on mongo

Peak 26,615.9/s at 4,096 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): 8,192 clients: 20,037.3/s answered, failed 2.0% (12,468 × 429 wow.command.busy).

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 4,096 | 26,615.9 | 144.38 | 333.82 | 798,477 | 0 | 1.93 | 0.26 | 0.21 / 0.32 | 12.7 | mongo 4.66, kafka 0.36 | 24.9 |  |
| 8,192 | 20,037.3 | 405.50 | 475.13 | 601,120 | 12,468 × http-429-wow.command.busy | 2.13 | 0.20 | 0.08 / 0.13 | 10.3 | – | 20.3 |  |

MongoDB over each step's window (serverStatus, every second): the journal's syncs and their mean time, the write concern's majority waits and their mean time, the writes' and transactions' mean latency, the transactions aborted, the retried commands, the write conflicts, and the write tickets in use.

| Clients | Journal syncs/s | Sync (ms) | Majority waits/s | Wait (ms) | Write (µs) | Transaction (µs) | Aborted/s | Retried/s | Conflicts/s | Tickets (mean / max) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 4,096 | 191.0 | 2.33 | 260.3 | 0.00 | 16,695 | – | 0.00 | 0.00 | 0.00 | 2.7 / 6 |
| 8,192 | 360.4 | 1.42 | 517.9 | 0.01 | 6,676 | – | 0.00 | 0.00 | 0.00 | 1.7 / 5 |

Load averages before: 16.69 11.81 12.16; after: 19.50 14.33 13.12.

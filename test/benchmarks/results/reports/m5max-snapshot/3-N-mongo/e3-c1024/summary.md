# 3-N-mongo/e3-c1024: E3 (uniform), 3 node(s) on mongo

Result 6,015.6/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): none.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1,024 | 6,015.6 | 141.31 | 518.14 | 360,933 | 0 | 0.69 | 0.10 | 0.51 / 0.56 | 13.7 | – | 57.5 |  |

MongoDB over each step's window (serverStatus, every second): the journal's syncs and their mean time, the write concern's majority waits and their mean time, the writes' and transactions' mean latency, the transactions aborted, the retried commands, the write conflicts, and the write tickets in use.

| Clients | Journal syncs/s | Sync (ms) | Majority waits/s | Wait (ms) | Write (µs) | Transaction (µs) | Aborted/s | Retried/s | Conflicts/s | Tickets (mean / max) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1,024 | 235.5 | 3.45 | 1,039.4 | 0.01 | 9,231 | – | 0.00 | 0.00 | 0.00 | 2.3 / 8 |

Load averages before: 43.63 33.04 27.01; after: 49.80 38.29 29.59.

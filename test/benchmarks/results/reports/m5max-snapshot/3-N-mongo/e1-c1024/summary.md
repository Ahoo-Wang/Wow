# 3-N-mongo/e1-c1024: E1 (uniform), 3 node(s) on mongo

Result 17,695.9/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 17,695.9/s at 1,024 clients, p99 152.6 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores. Containers is each of the benchmark's containers' CPU over the step's window, mean cores (its cgroup's).

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Containers | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1,024 | 17,695.9 | 43.52 | 152.57 | 1,061,752 | 0 | 1.37 | 0.21 | 0.47 / 0.52 | 13.2 | – | 51.0 |  |

MongoDB over each step's window (serverStatus, every second): the journal's syncs and their mean time, the write concern's majority waits and their mean time, the writes' and transactions' mean latency, the transactions aborted, the retried commands, the write conflicts, and the write tickets in use.

| Clients | Journal syncs/s | Sync (ms) | Majority waits/s | Wait (ms) | Write (µs) | Transaction (µs) | Aborted/s | Retried/s | Conflicts/s | Tickets (mean / max) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1,024 | 215.0 | 3.87 | 1,031.5 | 0.01 | 9,401 | – | 0.00 | 0.00 | 0.00 | 2.2 / 9 |

Load averages before: 30.90 27.25 24.50; after: 43.63 33.04 27.01.

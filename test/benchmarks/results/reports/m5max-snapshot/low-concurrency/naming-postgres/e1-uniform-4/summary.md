# naming/e1-uniform-4: E1 (uniform), 1 node(s) on postgres

Peak 2,153.4/s at 4 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 2,106.3/s at 4 clients, p99 3.3 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4 | 2,106.3 | 1.82 | 3.28 | 21,063 | 0 | 0.41 | – | – | – | – |  |
| 4 | 1,686.7 | 1.85 | 7.55 | 16,867 | 0 | 0.22 | – | – | – | – |  |
| 4 | 2,153.4 | 1.79 | 3.17 | 21,534 | 0 | 0.25 | – | – | – | – |  |
| 4 | 1,661.1 | 1.81 | 7.74 | 16,611 | 0 | 0.21 | – | – | – | – |  |
| 4 | 738.1 | 4.99 | 13.82 | 7,381 | 0 | 0.15 | – | – | – | – |  |
| 4 | 728.1 | 5.05 | 15.49 | 7,281 | 0 | 0.15 | – | – | – | – |  |
| 4 | 737.2 | 4.96 | 15.29 | 7,372 | 0 | 0.15 | – | – | – | – |  |
| 4 | 796.1 | 4.64 | 14.91 | 7,961 | 0 | 0.12 | – | – | – | – |  |
| 4 | 739.3 | 4.96 | 14.85 | 7,393 | 0 | 0.13 | – | – | – | – |  |
| 4 | 726.0 | 5.02 | 15.29 | 7,260 | 0 | 0.15 | – | – | – | – |  |
| 4 | 1,665.1 | 1.81 | 7.46 | 16,651 | 0 | 0.22 | – | – | – | – |  |
| 4 | 1,511.5 | 2.00 | 7.68 | 15,115 | 0 | 0.19 | – | – | – | – |  |

Load averages before: 9.07 8.09 8.81; after: 6.38 7.31 8.36.

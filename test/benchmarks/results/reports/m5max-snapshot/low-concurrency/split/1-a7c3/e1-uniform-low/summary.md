# 1-a7c3/e1-uniform-low: E1 (uniform), 1 node(s) on postgres

Peak 4,149.2/s at 16 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 4,149.2/s at 16 clients, p99 10.7 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 4 | 1,587.9 | 1.79 | 7.74 | 23,818 | 0 | 0.25 | 0.03 | 0.83 / 1.03 | 2.0 | 17.2 |  |
| 16 | 4,149.2 | 2.90 | 10.69 | 62,238 | 0 | 0.49 | 0.06 | 0.65 / 0.65 | 4.0 | 13.1 |  |
| 4 | 676.5 | 5.09 | 17.02 | 10,147 | 0 | 0.13 | 0.03 | 0.83 / 1.03 | 2.0 | 17.2 |  |
| 16 | 2,778.7 | 4.86 | 16.00 | 41,680 | 0 | 0.34 | 0.06 | 0.65 / 0.65 | 4.0 | 13.1 |  |

Load averages before: 31.52 13.19 9.98; after: 11.47 11.25 9.55.

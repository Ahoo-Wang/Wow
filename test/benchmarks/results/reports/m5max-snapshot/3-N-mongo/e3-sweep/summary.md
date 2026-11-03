# 3-N-mongo/e3-sweep: E3 (uniform), 3 node(s) on mongo

Peak 5,154.1/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 4,608.0/s at 256 clients, p99 111.6 ms.
Failed more than 1% (not a peak): none.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 171.0 | 5.76 | 11.46 | 5,129 | 0 | 0.07 | 0.05 | 0.14 / 0.30 | 1.7 | 26.5 |  |
| 4 | 493.6 | 6.82 | 18.43 | 14,808 | 0 | 0.09 | 0.06 | 0.10 / 0.21 | 4.2 | 16.3 |  |
| 16 | 1,438.5 | 10.56 | 21.50 | 43,156 | 0 | 0.25 | 0.05 | 0.12 / 0.16 | 9.7 | 11.5 |  |
| 64 | 2,871.3 | 21.50 | 41.47 | 86,140 | 0 | 0.46 | 0.10 | 0.13 / 0.20 | 11.4 | 20.5 |  |
| 256 | 4,608.0 | 52.73 | 111.61 | 138,240 | 0 | 0.57 | 0.10 | 0.13 / 0.17 | 13.3 | 24.5 |  |
| 1,024 | 5,154.1 | 160.77 | 528.38 | 154,623 | 0 | 0.64 | 0.10 | 0.11 / 0.17 | 13.6 | 24.4 |  |
| 4,096 | 2,087.7 | 1294.34 | 8388.61 | 62,632 | 163 × http-504-wow.wait.timeout | 0.28 | 0.04 | 0.08 / 0.10 | 13.9 | 35.6 |  |

Load averages before: 32.79 24.69 20.35; after: 34.75 24.65 20.94.

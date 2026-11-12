# 1-S-postgres/e1-zipf-sweep: E1 (zipf), 1 node(s) on postgres

Peak 17,090.6/s at 1,024 clients.
Sustainable (the highest step with a p99 of 200 ms or less and no error): 15,386.1/s at 256 clients, p99 52.2 ms.
Failed more than 1% (not a peak): 4,096 clients: 20,308.3/s answered, failed 12.0% (83,231 × 429 wow.command.busy). The throughput was still rising when the failures began.

Generator cores are the load generator's, of the 18 processors it had; Generator thread is its busiest thread's, in cores. A step is generator-bound at 80% of the processors or 0.9 of a core for one thread. Other CPU is the processes that are not the benchmark's, in cores, mean / max over the step's window; a step is disturbed at 2 cores on average or a foreign container at 0.5. Host CPU is every process's, OrbStack's VM among them, mean cores.

| Clients | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors | Generator cores | Generator thread | Other CPU | Host CPU | Load (1 min, max) | |
| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 524.3 | 1.37 | 4.16 | 15,728 | 0 | 0.18 | 0.06 | 0.25 / 0.45 | 1.2 | 19.1 |  |
| 4 | 684.1 | 4.93 | 16.00 | 20,523 | 0 | 0.13 | 0.04 | 0.15 / 0.25 | 1.8 | 11.7 |  |
| 16 | 2,466.7 | 4.96 | 18.05 | 74,002 | 0 | 0.31 | 0.06 | 0.12 / 0.22 | 3.2 | 7.1 |  |
| 64 | 7,402.5 | 6.56 | 24.83 | 222,074 | 0 | 0.76 | 0.11 | 0.10 / 0.18 | 6.5 | 5.9 |  |
| 256 | 15,386.1 | 14.53 | 52.22 | 461,583 | 0 | 1.50 | 0.17 | 0.12 / 0.15 | 8.5 | 10.3 |  |
| 1,024 | 17,090.6 | 19.33 | 389.12 | 512,719 | 0 | 1.58 | 0.21 | 0.13 / 0.16 | 9.3 | 10.0 |  |
| 4,096 | 20,308.3 | 63.49 | 1835.01 | 609,250 | 83,231 × http-429-wow.command.busy | 1.96 | 0.26 | 0.57 / 0.91 | 11.6 | 10.7 |  |

Load averages before: 22.93 14.64 11.20; after: 11.32 10.73 10.17.

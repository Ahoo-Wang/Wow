# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-231946-run-mongo-n1-saturation.

Open loop at 7833.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5067.7/s | 304062 | 165918 | 692.2 ms | 1441.8 ms | 2326.5 ms | 3342.3 ms | 4963.8 ms | 3702.8 ms |

Errors: {http-429-wow.command.busy=165918}

Load generator: 1.15 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.07 | 1187 MB | 879 MB | 194 | 3.140 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 66.2% | 209.6% | 2.17GiB / 15.6GiB |
| wow-e2e-bench-mongo | 912.4% | 1334.9% | 3.574GiB / 15.6GiB |

Store operations, mongo (seconds -0.308 to 60.443): {epoch=60.75100016593933, insert=309015, query=108967, update=335921, delete=0, getmore=83, command=110276}
Store operations, kafkaEndOffsets (seconds -30.02 to 62.142): {wow.transfer.account.state=466950}

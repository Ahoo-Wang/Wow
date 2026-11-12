# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 10, settleSeconds 3, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/p1-sweep-edges-b33c1b/test/benchmarks/results/e2e/20261111-161357-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8000.0/s | 959995 | 5 | 8.4 ms | 69.6 ms | 124.4 ms | 190.5 ms | 293.4 ms | 260.1 ms |

Errors: {generator-overload=5}

Load generator: 1.57 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.19 | 1509 MB | 966 MB | 172 | 0.742 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 35.9% | 129.8% | 1006MiB / 31.36GiB |
| wow-e2e-bench-mongo | 178.1% | 251.1% | 2.146GiB / 31.36GiB |

Store operations, mongo (seconds -0.777 to 120.706): {epoch=121.48300004005432, insert=966211, query=493, update=1035833, delete=0, getmore=0, command=5602}
Store operations, kafkaEndOffsets (seconds -29.821 to 121.58): {wow.transfer.account.state=1193066, wow.transfer.commands=0}

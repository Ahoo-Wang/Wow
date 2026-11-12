# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/p1-sweep-edges-b33c1b/test/benchmarks/results/e2e/20261111-160242-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7999.8/s | 959978 | 22 | 6.6 ms | 24.2 ms | 108.0 ms | 177.2 ms | 287.8 ms | 260.1 ms |

Errors: {generator-overload=22}

Load generator: 1.64 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.30 | 1547 MB | 969 MB | 168 | 0.705 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 36.1% | 124.3% | 993.8MiB / 31.36GiB |
| wow-e2e-bench-mongo | 183.7% | 271.8% | 2.231GiB / 31.36GiB |

Store operations, mongo (seconds -0.788 to 120.668): {epoch=121.45600008964539, insert=966231, query=492, update=1037577, delete=0, getmore=0, command=5596}
Store operations, kafkaEndOffsets (seconds -29.85 to 122.36): {wow.transfer.account.state=1193011, wow.transfer.commands=0}

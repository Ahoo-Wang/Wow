# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka mongo, storeWriteIops , primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoowang/work/ahoo/Wow/.claude/worktrees/p1-sweep-edges-b33c1b/test/benchmarks/results/e2e/20261111-153449-run-mongo-n1-saturation.

Open loop at 8000.0/s, uniform over 100000 accounts, 30 s warm-up, 120 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7992.9/s | 959143 | 857 | 7.7 ms | 110.1 ms | 206.8 ms | 284.7 ms | 370.0 ms | 335.9 ms |

Errors: {generator-overload=857}

Load generator: 1.62 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.29 | 1517 MB | 952 MB | 171 | 0.738 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 37.8% | 133.1% | 1005MiB / 31.36GiB |
| wow-e2e-bench-mongo | 183.5% | 310.1% | 2.144GiB / 31.36GiB |

Store operations, mongo (seconds -0.758 to 120.748): {epoch=121.50599980354309, insert=965215, query=493, update=1034284, delete=0, getmore=0, command=5594}
Store operations, kafkaEndOffsets (seconds -29.859 to 121.615): {wow.transfer.account.state=1191953, wow.transfer.commands=0}

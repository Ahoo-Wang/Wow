# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 36, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-mongo-up.

Open loop at 1723.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1723.0/s | 103380 | 0 | 6.6 ms | 26.2 ms | 53.0 ms | 100.4 ms | 180.9 ms | 130.6 ms |

Load generator: 0.39 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.63 | 1107 MB | 799 MB | 48 | 0.295 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 69.0% | 176.6% | 1.817GiB / 7.748GiB |
| wow-e2e-bench-mongo | 356.6% | 543.5% | 1.99GiB / 7.748GiB |

Store operations, mongo (seconds -0.955 to 60.526): {epoch=61.48099994659424, insert=105014, query=77976, update=133888, delete=0, getmore=0, command=78469}
Store operations, kafkaEndOffsets (seconds -30.031 to 62.254): {wow.transfer.account.state=152843}

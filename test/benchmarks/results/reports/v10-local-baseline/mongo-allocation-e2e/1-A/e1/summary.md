# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 32, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/out/1-A/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 4.6 ms | 12.6 ms | 25.6 ms | 180.2 ms | 245.1 ms | 217.1 ms |

Load generator: 0.42 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.61 | 1242 MB | 849 MB | 46 | 0.198 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 60.0% | 181.4% | 1.846GiB / 7.748GiB |
| wow-e2e-bench-mongo | 285.1% | 401.8% | 1.316GiB / 7.748GiB |

Store operations, mongo (seconds -0.364 to 60.976): {epoch=61.33999991416931, insert=103812, query=77086, update=133964, delete=0, getmore=0, command=77553}
Store operations, kafkaEndOffsets (seconds -29.762 to 62.199): {wow.transfer.account.state=152782}

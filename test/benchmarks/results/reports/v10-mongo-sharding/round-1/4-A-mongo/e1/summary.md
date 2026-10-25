# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/sharding-ab/4-A-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.0 ms | 2.1 ms | 3.9 ms | 9.9 ms | 29.9 ms | 25.0 ms |

Load generator: 0.27 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.99 | 1102 MB | 755 MB | 26 | 0.166 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 33.6% | 128.9% | 1.718GiB / 7.748GiB |
| wow-e2e-bench-mongo | 88.5% | 149.4% | 1.889GiB / 7.748GiB |

Store operations, mongo (seconds -0.471 to 60.738): {epoch=61.20899987220764, insert=104001, query=325, update=135172, delete=0, getmore=0, command=784}
Store operations, kafkaEndOffsets (seconds -29.811 to 62.006): {wow.transfer.account.state=153002}

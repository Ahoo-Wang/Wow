# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/sharding-ab/3-B-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.0 ms | 2.2 ms | 3.7 ms | 7.4 ms | 16.8 ms | 8.0 ms |

Load generator: 0.27 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.99 | 1067 MB | 744 MB | 23 | 0.130 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 34.8% | 115.4% | 1.71GiB / 7.748GiB |
| wow-e2e-bench-mongo | 95.8% | 155.4% | 1.886GiB / 7.748GiB |

Store operations, mongo (seconds -0.554 to 60.647): {epoch=61.20099997520447, insert=104141, query=325, update=135312, delete=0, getmore=0, command=782}
Store operations, kafkaEndOffsets (seconds -29.776 to 62.046): {wow.transfer.account.state=152807}

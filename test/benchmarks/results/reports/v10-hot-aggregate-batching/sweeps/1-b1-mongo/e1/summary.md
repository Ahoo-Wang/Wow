# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 34, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch/1-b1-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 5.2 ms | 51.5 ms | 136.2 ms | 234.5 ms | 302.3 ms | 252.9 ms |

Load generator: 0.42 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.42 | 983 MB | 819 MB | 22 | 0.236 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 67.4% | 192.6% | 2.027GiB / 7.748GiB |
| wow-e2e-bench-mongo | 264.5% | 542.0% | 1.335GiB / 7.748GiB |

Store operations, mongo (seconds -0.986 to 60.855): {epoch=61.84100008010864, insert=104870, query=7181, update=134522, delete=0, getmore=19, command=7330}
Store operations, kafkaEndOffsets (seconds -29.74 to 62.298): {wow.transfer.account.state=164400}

# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 15, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/merge/2-B-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.6 ms | 8.5 ms | 20.5 ms | 98.3 ms | 154.5 ms | 135.2 ms |

Load generator: 0.34 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.00 | 927 MB | 755 MB | 21 | 0.135 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.6% | 173.4% | 1.707GiB / 7.748GiB |
| wow-e2e-bench-mongo | 122.1% | 198.1% | 1.302GiB / 7.748GiB |

Store operations, mongo (seconds -0.592 to 60.671): {epoch=61.26300001144409, insert=104206, query=324, update=135376, delete=0, getmore=0, command=772}
Store operations, kafkaEndOffsets (seconds -29.828 to 62.002): {wow.transfer.account.state=152967}

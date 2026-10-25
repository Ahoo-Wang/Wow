# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 17, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers-tmpfs/1-A-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.2 ms | 2.8 ms | 7.4 ms | 18.3 ms | 39.1 ms | 26.8 ms |

Load generator: 0.32 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.19 | 947 MB | 733 MB | 36 | 0.146 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.3% | 174.1% | 1.809GiB / 7.748GiB |
| wow-e2e-bench-mongo | 154.2% | 250.8% | 2.069GiB / 7.748GiB |

Store operations, mongo (seconds -0.078 to 60.225): {epoch=60.30299997329712, insert=103312, query=320, update=133148, delete=0, getmore=0, command=758}
Store operations, kafkaEndOffsets (seconds -29.74 to 62.019): {wow.transfer.account.state=151972}

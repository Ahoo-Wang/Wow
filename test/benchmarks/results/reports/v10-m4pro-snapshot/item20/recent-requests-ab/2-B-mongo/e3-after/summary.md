# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 15, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/rr/ab/2-B-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.8 ms | 12.0 ms | 20.6 ms | 60.4 ms | 95.9 ms | 79.4 ms |

Load generator: 0.23 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.29 | 950 MB | 811 MB | 16 | 0.155 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.2% | 176.7% | 1.089GiB / 7.748GiB |
| wow-e2e-bench-mongo | 170.2% | 372.9% | 4.443GiB / 7.748GiB |

Store operations, mongo (seconds -0.508 to 40.725): {epoch=41.23300004005432, insert=85058, query=732, update=105911, delete=0, getmore=49, command=640}
Store operations, kafkaEndOffsets (seconds -20.022 to 41.984): {wow.transfer.account.state=133344}

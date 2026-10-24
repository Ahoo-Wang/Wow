# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 27, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/threads/2-P-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 4.2 ms | 11.9 ms | 24.7 ms | 206.8 ms | 265.9 ms | 244.7 ms |

Load generator: 0.41 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.62 | 1101 MB | 834 MB | 41 | 0.232 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.3% | 177.1% | 1.87GiB / 7.748GiB |
| wow-e2e-bench-mongo | 275.4% | 372.6% | 1.423GiB / 7.748GiB |

Store operations, mongo (seconds -0.518 to 60.869): {epoch=61.38700008392334, insert=104075, query=77282, update=133727, delete=0, getmore=0, command=77728}
Store operations, kafkaEndOffsets (seconds -29.766 to 62.175): {wow.transfer.account.state=153014}

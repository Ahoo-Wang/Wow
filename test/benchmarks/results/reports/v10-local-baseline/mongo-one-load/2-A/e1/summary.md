# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 31, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/out/2-A/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 4.4 ms | 11.9 ms | 23.9 ms | 169.0 ms | 228.6 ms | 206.8 ms |

Load generator: 0.41 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.56 | 1217 MB | 805 MB | 46 | 0.209 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.1% | 170.6% | 1.745GiB / 7.748GiB |
| wow-e2e-bench-mongo | 284.5% | 440.7% | 1.321GiB / 7.748GiB |

Store operations, mongo (seconds -0.468 to 60.865): {epoch=61.33299994468689, insert=103988, query=77216, update=134156, delete=0, getmore=0, command=77665}
Store operations, kafkaEndOffsets (seconds -29.782 to 62.147): {wow.transfer.account.state=152807}

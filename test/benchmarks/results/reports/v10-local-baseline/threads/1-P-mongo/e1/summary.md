# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 28, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/threads/1-P-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 4.6 ms | 12.2 ms | 23.7 ms | 186.4 ms | 248.5 ms | 229.4 ms |

Load generator: 0.40 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.69 | 1100 MB | 856 MB | 43 | 0.241 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 59.3% | 174.5% | 1.84GiB / 7.748GiB |
| wow-e2e-bench-mongo | 295.5% | 405.3% | 1.327GiB / 7.748GiB |

Store operations, mongo (seconds -0.419 to 60.934): {epoch=61.35300016403198, insert=103908, query=77158, update=134068, delete=0, getmore=0, command=77606}
Store operations, kafkaEndOffsets (seconds -29.808 to 62.14): {wow.transfer.account.state=153009}

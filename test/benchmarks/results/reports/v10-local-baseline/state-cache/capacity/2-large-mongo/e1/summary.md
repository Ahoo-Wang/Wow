# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 23, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache-mongo/2-large-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 3.4 ms | 49.2 ms | 219.1 ms | 532.5 ms | 815.9 ms | 671.7 ms |

Load generator: 0.43 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.37 | 1163 MB | 961 MB | 41 | 0.205 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 67.9% | 219.0% | 1.901GiB / 7.748GiB |
| wow-e2e-bench-mongo | 267.7% | 684.5% | 1.307GiB / 7.748GiB |

Store operations, mongo (seconds -0.15 to 60.309): {epoch=60.45900011062622, insert=103400, query=36209, update=132595, delete=0, getmore=0, command=754}
Store operations, kafkaEndOffsets (seconds -29.776 to 62.223): {wow.transfer.account.state=152602}

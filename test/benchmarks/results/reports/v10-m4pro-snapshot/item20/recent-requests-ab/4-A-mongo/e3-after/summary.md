# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/rr/ab/4-A-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.2 ms | 6.8 ms | 11.1 ms | 19.8 ms | 38.1 ms | 25.2 ms |

Load generator: 0.18 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.27 | 1044 MB | 813 MB | 18 | 0.166 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 40.2% | 167.2% | 1.299GiB / 7.748GiB |
| wow-e2e-bench-mongo | 142.7% | 206.6% | 4.552GiB / 7.748GiB |

Store operations, mongo (seconds -0.434 to 40.724): {epoch=41.157999992370605, insert=84901, query=723, update=105751, delete=0, getmore=49, command=656}
Store operations, kafkaEndOffsets (seconds -20.003 to 41.918): {wow.transfer.account.state=133546}

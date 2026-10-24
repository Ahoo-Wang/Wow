# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 33, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/seq/1-F2/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.8 ms | 8.1 ms | 30.8 ms | 164.9 ms | 215.4 ms | 196.6 ms |

Load generator: 0.33 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.10 | 959 MB | 812 MB | 25 | 0.152 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.2% | 174.8% | 1.859GiB / 7.748GiB |
| wow-e2e-bench-mongo | 177.4% | 298.4% | 1.315GiB / 7.748GiB |

Store operations, mongo (seconds -0.493 to 60.775): {epoch=61.26800012588501, insert=104037, query=36435, update=134199, delete=0, getmore=0, command=5359}
Store operations, kafkaEndOffsets (seconds -29.777 to 62.039): {wow.transfer.account.state=152661}

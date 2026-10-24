# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 31, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/out/2-D2/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 4.5 ms | 12.2 ms | 25.1 ms | 130.0 ms | 222.7 ms | 190.5 ms |

Load generator: 0.40 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.63 | 1121 MB | 801 MB | 54 | 0.214 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 59.6% | 165.8% | 1.902GiB / 7.748GiB |
| wow-e2e-bench-mongo | 285.6% | 400.7% | 1.33GiB / 7.748GiB |

Store operations, mongo (seconds -0.552 to 60.776): {epoch=61.32799983024597, insert=104141, query=77325, update=134315, delete=0, getmore=0, command=77771}
Store operations, kafkaEndOffsets (seconds -29.782 to 62.244): {wow.transfer.account.state=152651}

# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 28, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/seq/2-F2/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.3 ms | 8.1 ms | 24.7 ms | 252.9 ms | 309.4 ms | 292.9 ms |

Load generator: 0.35 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.18 | 915 MB | 815 MB | 25 | 0.155 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.4% | 176.3% | 2.073GiB / 7.748GiB |
| wow-e2e-bench-mongo | 199.1% | 294.9% | 1.465GiB / 7.748GiB |

Store operations, mongo (seconds -0.235 to 60.056): {epoch=60.29100012779236, insert=103590, query=36282, update=133235, delete=0, getmore=0, command=5356}
Store operations, kafkaEndOffsets (seconds -29.8 to 62.091): {wow.transfer.account.state=152311}

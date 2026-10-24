# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 26, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/threads/1-D2-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 4.2 ms | 12.5 ms | 25.5 ms | 166.9 ms | 217.3 ms | 205.8 ms |

Load generator: 0.41 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.55 | 1066 MB | 815 MB | 59 | 0.215 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 56.2% | 169.2% | 1.754GiB / 7.748GiB |
| wow-e2e-bench-mongo | 287.2% | 473.2% | 1.309GiB / 7.748GiB |

Store operations, mongo (seconds -0.482 to 60.863): {epoch=61.34500002861023, insert=104010, query=77228, update=133653, delete=0, getmore=0, command=77662}
Store operations, kafkaEndOffsets (seconds -29.768 to 62.077): {wow.transfer.account.state=152783}

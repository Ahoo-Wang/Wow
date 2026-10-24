# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 26, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch-clean/4-b1-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.8 ms | 4.7 ms | 8.8 ms | 45.8 ms | 111.2 ms | 83.5 ms |

Load generator: 0.30 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.01 | 1072 MB | 807 MB | 23 | 0.119 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 47.2% | 174.9% | 1.726GiB / 7.748GiB |
| wow-e2e-bench-mongo | 166.9% | 236.8% | 1.303GiB / 7.748GiB |

Store operations, mongo (seconds -0.608 to 60.655): {epoch=61.26300001144409, insert=104236, query=4716, update=134386, delete=0, getmore=0, command=5164}
Store operations, kafkaEndOffsets (seconds -29.814 to 62.074): {wow.transfer.account.state=153105}

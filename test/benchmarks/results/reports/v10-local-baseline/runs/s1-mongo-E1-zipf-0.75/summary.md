# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 36, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-mongo-up.

Open loop at 2309.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2309.0/s | 138540 | 0 | 2.2 ms | 10.5 ms | 95.7 ms | 356.4 ms | 560.1 ms | 536.6 ms |

Load generator: 0.42 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.35 | 1095 MB | 814 MB | 32 | 0.107 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 48.3% | 166.0% | 1.906GiB / 7.748GiB |
| wow-e2e-bench-mongo | 197.4% | 296.4% | 2.692GiB / 7.748GiB |

Store operations, mongo (seconds -0.508 to 60.801): {epoch=61.30899977684021, insert=139702, query=7080, update=166349, delete=0, getmore=31, command=7404}
Store operations, kafkaEndOffsets (seconds -30.01 to 62.049): {wow.transfer.account.state=192808}

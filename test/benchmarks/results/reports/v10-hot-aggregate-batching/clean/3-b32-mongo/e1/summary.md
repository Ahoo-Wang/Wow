# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 25, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch-clean/3-b32-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.8 ms | 4.8 ms | 9.4 ms | 61.4 ms | 129.6 ms | 106.5 ms |

Load generator: 0.31 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.03 | 1143 MB | 812 MB | 23 | 0.125 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.8% | 170.0% | 1.736GiB / 7.748GiB |
| wow-e2e-bench-mongo | 164.0% | 265.7% | 1.458GiB / 7.748GiB |

Store operations, mongo (seconds -0.562 to 60.684): {epoch=61.24600005149841, insert=104162, query=4718, update=134662, delete=0, getmore=0, command=5155}
Store operations, kafkaEndOffsets (seconds -29.843 to 62.052): {wow.transfer.account.state=153111}

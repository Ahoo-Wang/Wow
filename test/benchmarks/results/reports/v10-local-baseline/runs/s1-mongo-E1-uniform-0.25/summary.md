# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 36, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-mongo-up.

Open loop at 861.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 861.0/s | 51660 | 0 | 2.3 ms | 6.7 ms | 14.9 ms | 66.6 ms | 93.4 ms | 87.6 ms |

Load generator: 0.19 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.86 | 1103 MB | 823 MB | 24 | 0.133 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.3% | 177.0% | 1.753GiB / 7.748GiB |
| wow-e2e-bench-mongo | 134.1% | 244.8% | 1.693GiB / 7.748GiB |

Store operations, mongo (seconds -0.563 to 60.72): {epoch=61.282999992370605, insert=52138, query=38869, update=82878, delete=0, getmore=0, command=39131}
Store operations, kafkaEndOffsets (seconds -29.758 to 62.076): {wow.transfer.account.state=76477}

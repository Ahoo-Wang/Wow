# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 32, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache-mongo/2-default-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 6.1 ms | 20.6 ms | 46.6 ms | 188.4 ms | 247.4 ms | 228.4 ms |

Load generator: 0.42 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.60 | 1082 MB | 840 MB | 39 | 0.210 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 66.8% | 169.5% | 2.043GiB / 7.748GiB |
| wow-e2e-bench-mongo | 347.0% | 532.2% | 1.291GiB / 7.748GiB |

Store operations, mongo (seconds -0.243 to 60.166): {epoch=60.40899991989136, insert=103602, query=76914, update=132229, delete=0, getmore=0, command=77322}
Store operations, kafkaEndOffsets (seconds -29.773 to 62.262): {wow.transfer.account.state=152486}

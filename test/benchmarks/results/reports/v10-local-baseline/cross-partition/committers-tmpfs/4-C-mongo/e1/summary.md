# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 13, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers-tmpfs/4-C-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.0 ms | 2.6 ms | 4.3 ms | 9.2 ms | 16.3 ms | 10.0 ms |

Load generator: 0.28 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.04 | 1130 MB | 759 MB | 23 | 0.116 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 40.6% | 158.8% | 1.766GiB / 7.748GiB |
| wow-e2e-bench-mongo | 112.4% | 209.0% | 2.02GiB / 7.748GiB |

Store operations, mongo (seconds -0.266 to 60.94): {epoch=61.206000089645386, insert=103647, query=325, update=134304, delete=0, getmore=0, command=792}
Store operations, kafkaEndOffsets (seconds -29.777 to 61.955): {wow.transfer.account.state=152958}

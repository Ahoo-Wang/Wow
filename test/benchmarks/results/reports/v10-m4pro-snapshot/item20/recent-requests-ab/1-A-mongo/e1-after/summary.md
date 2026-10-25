# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 5, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/rr/ab/1-A-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 68800 | 0 | 1.5 ms | 6.8 ms | 19.2 ms | 56.1 ms | 83.2 ms | 64.3 ms |

Load generator: 0.33 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.12 | 1050 MB | 823 MB | 17 | 0.181 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 55.1% | 173.7% | 1.034GiB / 7.748GiB |
| wow-e2e-bench-mongo | 182.1% | 366.5% | 4.561GiB / 7.748GiB |

Store operations, mongo (seconds -0.283 to 40.973): {epoch=41.25600004196167, insert=69244, query=771, update=89879, delete=0, getmore=49, command=598}
Store operations, kafkaEndOffsets (seconds -19.73 to 41.908): {wow.transfer.account.state=109702}

# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/rr/ab/4-A-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 68800 | 0 | 1.1 ms | 3.1 ms | 5.8 ms | 11.2 ms | 18.0 ms | 11.9 ms |

Load generator: 0.30 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.00 | 1074 MB | 812 MB | 16 | 0.086 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.5% | 164.6% | 1011MiB / 7.748GiB |
| wow-e2e-bench-mongo | 144.1% | 227.2% | 4.456GiB / 7.748GiB |

Store operations, mongo (seconds -0.365 to 40.805): {epoch=41.170000076293945, insert=69411, query=750, update=89686, delete=0, getmore=43, command=534}
Store operations, kafkaEndOffsets (seconds -19.791 to 41.843): {wow.transfer.account.state=122697}

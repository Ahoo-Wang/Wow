# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 26, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/seq/1-H/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.7 ms | 6.6 ms | 15.9 ms | 63.0 ms | 121.6 ms | 106.5 ms |

Load generator: 0.32 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.06 | 942 MB | 800 MB | 23 | 0.128 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.1% | 168.4% | 1.861GiB / 7.748GiB |
| wow-e2e-bench-mongo | 183.3% | 281.3% | 1.324GiB / 7.748GiB |

Store operations, mongo (seconds -0.446 to 60.861): {epoch=61.306999921798706, insert=103955, query=4955, update=134615, delete=0, getmore=0, command=5413}
Store operations, kafkaEndOffsets (seconds -29.782 to 61.987): {wow.transfer.account.state=152561}

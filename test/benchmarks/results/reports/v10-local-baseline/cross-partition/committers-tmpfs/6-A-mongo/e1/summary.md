# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 19, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers-tmpfs/6-A-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.4 ms | 3.9 ms | 7.7 ms | 16.4 ms | 32.4 ms | 23.3 ms |

Load generator: 0.35 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.23 | 1011 MB | 751 MB | 23 | 0.145 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 54.8% | 186.4% | 1.684GiB / 7.748GiB |
| wow-e2e-bench-mongo | 171.8% | 292.8% | 2.049GiB / 7.748GiB |

Store operations, mongo (seconds -0.342 to 60.997): {epoch=61.33899998664856, insert=103776, query=324, update=133920, delete=0, getmore=0, command=824}
Store operations, kafkaEndOffsets (seconds -29.77 to 62.152): {wow.transfer.account.state=152759}

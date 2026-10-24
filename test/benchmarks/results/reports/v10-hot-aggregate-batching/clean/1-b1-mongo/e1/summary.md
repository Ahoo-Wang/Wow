# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 31, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch-clean/1-b1-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.1 ms | 6.0 ms | 11.6 ms | 32.1 ms | 46.8 ms | 38.7 ms |

Load generator: 0.31 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.09 | 938 MB | 794 MB | 22 | 0.128 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.0% | 172.0% | 1.7GiB / 7.748GiB |
| wow-e2e-bench-mongo | 180.8% | 278.0% | 1.288GiB / 7.748GiB |

Store operations, mongo (seconds -0.382 to 60.919): {epoch=61.300999879837036, insert=103835, query=4705, update=133983, delete=0, getmore=0, command=5150}
Store operations, kafkaEndOffsets (seconds -29.813 to 62.058): {wow.transfer.account.state=152881}

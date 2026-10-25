# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers/1-A-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.1 ms | 6.7 ms | 15.2 ms | 68.1 ms | 142.3 ms | 114.2 ms |

Load generator: 0.33 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.09 | 895 MB | 737 MB | 22 | 0.136 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.7% | 172.7% | 1.696GiB / 7.748GiB |
| wow-e2e-bench-mongo | 178.6% | 267.8% | 1.292GiB / 7.748GiB |

Store operations, mongo (seconds -0.1 to 60.228): {epoch=60.32799983024597, insert=103351, query=323, update=133495, delete=0, getmore=0, command=747}
Store operations, kafkaEndOffsets (seconds -29.805 to 62.102): {wow.transfer.account.state=152301}

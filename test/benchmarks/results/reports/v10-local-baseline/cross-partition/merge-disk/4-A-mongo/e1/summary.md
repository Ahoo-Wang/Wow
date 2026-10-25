# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 18, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/merge/4-A-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.4 ms | 9.5 ms | 27.5 ms | 134.1 ms | 187.0 ms | 175.1 ms |

Load generator: 0.38 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.17 | 898 MB | 756 MB | 22 | 0.128 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 55.7% | 184.9% | 2.098GiB / 7.748GiB |
| wow-e2e-bench-mongo | 203.5% | 341.1% | 1.299GiB / 7.748GiB |

Store operations, mongo (seconds -0.297 to 60.054): {epoch=60.35099983215332, insert=103693, query=321, update=133326, delete=0, getmore=0, command=745}
Store operations, kafkaEndOffsets (seconds -29.751 to 62.086): {wow.transfer.account.state=152430}

# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 30, settleSeconds 14, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/1-A-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 7.6 ms | 188.4 ms | 503.8 ms | 872.4 ms | 1330.8 ms | 1089.5 ms |

Load generator: 0.50 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.40 | 903 MB | 844 MB | 24 | 0.496 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 82.8% | 257.1% | 2.006GiB / 7.748GiB |
| wow-e2e-bench-mongo | 422.5% | 935.9% | 2.109GiB / 7.748GiB |

Store operations, mongo (seconds -0.918 to 60.287): {epoch=61.204999923706055, insert=104831, query=25046, update=132902, delete=0, getmore=0, command=25524}
Store operations, kafkaEndOffsets (seconds -29.89 to 62.446): {wow.transfer.account.state=150369}

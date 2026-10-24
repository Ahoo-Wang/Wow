# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 39, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bytes/1-F-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 9.9 ms | 45.1 ms | 75.3 ms | 113.7 ms | 205.4 ms | 120.3 ms |

Load generator: 0.39 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.52 | 1136 MB | 852 MB | 41 | 0.259 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 77.8% | 180.9% | 1.77GiB / 7.748GiB |
| wow-e2e-bench-mongo | 439.5% | 654.3% | 1.614GiB / 7.748GiB |

Store operations, mongo (seconds -0.818 to 60.89): {epoch=61.70799994468689, insert=104645, query=77669, update=132073, delete=0, getmore=0, command=78081}
Store operations, kafkaEndOffsets (seconds -29.671 to 62.215): {wow.transfer.account.state=152126}

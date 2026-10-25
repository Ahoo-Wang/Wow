# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/sharding-ab/2-B-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.0 ms | 2.3 ms | 3.7 ms | 7.2 ms | 12.1 ms | 6.7 ms |

Load generator: 0.25 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.90 | 1054 MB | 688 MB | 24 | 0.125 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 37.2% | 147.4% | 1.869GiB / 7.748GiB |
| wow-e2e-bench-mongo | 103.0% | 164.7% | 1.887GiB / 7.748GiB |

Store operations, mongo (seconds -0.352 to 60.871): {epoch=61.223000049591064, insert=103796, query=324, update=134964, delete=0, getmore=0, command=784}
Store operations, kafkaEndOffsets (seconds -29.785 to 62.035): {wow.transfer.account.state=152740}

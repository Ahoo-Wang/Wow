# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/sharding-ab/1-A-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 3.2 ms | 9.8 ms | 18.9 ms | 105.0 ms | 147.4 ms | 129.0 ms |

Load generator: 0.19 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.13 | 870 MB | 744 MB | 24 | 0.173 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.9% | 171.9% | 1.857GiB / 7.748GiB |
| wow-e2e-bench-mongo | 168.3% | 314.4% | 2.718GiB / 7.748GiB |

Store operations, mongo (seconds -0.42 to 60.892): {epoch=61.312000036239624, insert=126869, query=324, update=157630, delete=0, getmore=0, command=942}
Store operations, kafkaEndOffsets (seconds -29.962 to 62.064): {wow.transfer.account.state=187000}

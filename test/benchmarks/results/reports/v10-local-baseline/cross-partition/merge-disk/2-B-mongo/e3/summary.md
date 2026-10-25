# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 15, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/merge/2-B-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 7.6 ms | 33.0 ms | 75.8 ms | 274.4 ms | 330.6 ms | 305.2 ms |

Load generator: 0.23 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.14 | 867 MB | 782 MB | 20 | 0.264 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.3% | 192.3% | 1.981GiB / 7.748GiB |
| wow-e2e-bench-mongo | 138.9% | 294.6% | 1.481GiB / 7.748GiB |

Store operations, mongo (seconds -0.425 to 60.879): {epoch=61.30400013923645, insert=126876, query=324, update=157645, delete=0, getmore=0, command=953}
Store operations, kafkaEndOffsets (seconds -29.99 to 62.181): {wow.transfer.account.state=186920}

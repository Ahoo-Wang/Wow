# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 15, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/e3/runs/mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 2.2 ms | 6.0 ms | 10.8 ms | 19.6 ms | 30.8 ms | 24.2 ms |

Load generator: 0.17 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.14 | 967 MB | 782 MB | 24 | 0.155 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 37.2% | 140.1% | 1.857GiB / 7.748GiB |
| wow-e2e-bench-mongo | 126.1% | 187.6% | 2.703GiB / 7.748GiB |

Store operations, mongo (seconds -0.538 to 60.676): {epoch=61.21399998664856, insert=127115, query=324, update=158387, delete=0, getmore=0, command=958}
Store operations, kafkaEndOffsets (seconds -30.006 to 62.099): {wow.transfer.account.state=187337}

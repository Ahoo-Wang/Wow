# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 18, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/merge/4-A-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 5.0 ms | 22.1 ms | 55.3 ms | 259.1 ms | 319.9 ms | 299.0 ms |

Load generator: 0.24 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.34 | 895 MB | 789 MB | 23 | 0.200 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.0% | 178.4% | 1.984GiB / 7.748GiB |
| wow-e2e-bench-mongo | 213.6% | 329.3% | 1.563GiB / 7.748GiB |

Store operations, mongo (seconds -0.467 to 60.892): {epoch=61.35899996757507, insert=126968, query=324, update=157222, delete=0, getmore=0, command=939}
Store operations, kafkaEndOffsets (seconds -30.001 to 62.208): {wow.transfer.account.state=186686}

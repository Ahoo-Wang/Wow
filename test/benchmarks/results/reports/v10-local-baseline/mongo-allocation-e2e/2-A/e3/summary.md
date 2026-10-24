# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 31, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/out/2-A/up.

Open loop at 714.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 714.0/s | 42840 | 0 | 9.2 ms | 26.1 ms | 45.1 ms | 183.3 ms | 256.3 ms | 226.3 ms |

Load generator: 0.21 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.51 | 1107 MB | 814 MB | 45 | 0.208 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 59.3% | 181.3% | 1.988GiB / 7.748GiB |
| wow-e2e-bench-mongo | 304.6% | 477.5% | 1.519GiB / 7.748GiB |

Store operations, mongo (seconds -0.256 to 60.083): {epoch=60.33899998664856, insert=129063, query=63862, update=158660, delete=0, getmore=0, command=64450}
Store operations, kafkaEndOffsets (seconds -30.018 to 62.121): {wow.transfer.account.state=190241}

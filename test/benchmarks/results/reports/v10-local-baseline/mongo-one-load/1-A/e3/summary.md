# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 41, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/out/1-A/up.

Open loop at 714.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 714.0/s | 42840 | 0 | 21.0 ms | 82.4 ms | 122.4 ms | 237.6 ms | 329.9 ms | 307.2 ms |

Load generator: 0.27 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.55 | 1098 MB | 837 MB | 42 | 0.282 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 76.7% | 184.1% | 1.989GiB / 7.748GiB |
| wow-e2e-bench-mongo | 422.5% | 612.0% | 1.538GiB / 7.748GiB |

Store operations, mongo (seconds -0.978 to 60.644): {epoch=61.622000217437744, insert=130591, query=64595, update=159664, delete=0, getmore=0, command=65173}
Store operations, kafkaEndOffsets (seconds -30.028 to 62.337): {wow.transfer.account.state=190606}

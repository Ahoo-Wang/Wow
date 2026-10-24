# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 31, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/out/2-A/up.

Open loop at 714.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 714.0/s | 42840 | 0 | 8.0 ms | 25.2 ms | 50.2 ms | 163.8 ms | 213.3 ms | 205.8 ms |

Load generator: 0.19 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.40 | 1099 MB | 850 MB | 46 | 0.192 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.3% | 173.1% | 1.995GiB / 7.748GiB |
| wow-e2e-bench-mongo | 286.7% | 374.6% | 1.54GiB / 7.748GiB |

Store operations, mongo (seconds -0.347 to 60.026): {epoch=60.37299990653992, insert=129259, query=63944, update=159358, delete=0, getmore=0, command=64542}
Store operations, kafkaEndOffsets (seconds -30.017 to 62.063): {wow.transfer.account.state=190579}

# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 39, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bytes/1-F-mongo/up.

Open loop at 722.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 722.0/s | 43320 | 0 | 61.7 ms | 185.3 ms | 272.4 ms | 540.7 ms | 643.9 ms | 626.7 ms |

Load generator: 0.28 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.67 | 1107 MB | 850 MB | 37 | 0.362 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 97.6% | 221.6% | 1.811GiB / 7.748GiB |
| wow-e2e-bench-mongo | 560.0% | 836.0% | 1.829GiB / 7.748GiB |

Store operations, mongo (seconds -0.458 to 60.742): {epoch=61.200000047683716, insert=131079, query=64757, update=158747, delete=0, getmore=0, command=65322}
Store operations, kafkaEndOffsets (seconds -29.979 to 62.407): {wow.transfer.account.state=190685}

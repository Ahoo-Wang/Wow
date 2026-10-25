# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers/4-C-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 7.6 ms | 45.8 ms | 233.5 ms | 436.2 ms | 562.2 ms | 532.5 ms |

Load generator: 0.22 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.17 | 908 MB | 778 MB | 21 | 0.205 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 61.4% | 193.1% | 2.017GiB / 7.748GiB |
| wow-e2e-bench-mongo | 241.4% | 449.1% | 1.507GiB / 7.748GiB |

Store operations, mongo (seconds -0.445 to 60.155): {epoch=60.59999990463257, insert=126918, query=323, update=156654, delete=0, getmore=0, command=910}
Store operations, kafkaEndOffsets (seconds -29.995 to 62.168): {wow.transfer.account.state=187252}

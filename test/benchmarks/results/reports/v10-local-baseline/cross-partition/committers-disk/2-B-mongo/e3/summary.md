# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 18, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers/2-B-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 7.7 ms | 264.2 ms | 598.0 ms | 1105.9 ms | 1226.6 ms | 1204.2 ms |

Load generator: 0.21 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.05 | 923 MB | 782 MB | 20 | 0.333 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.5% | 211.2% | 1.863GiB / 7.748GiB |
| wow-e2e-bench-mongo | 177.4% | 491.0% | 1.484GiB / 7.748GiB |

Store operations, mongo (seconds -0.629 to 60.789): {epoch=61.41799998283386, insert=127310, query=325, update=157565, delete=0, getmore=0, command=945}
Store operations, kafkaEndOffsets (seconds -30.014 to 62.266): {wow.transfer.account.state=187465}

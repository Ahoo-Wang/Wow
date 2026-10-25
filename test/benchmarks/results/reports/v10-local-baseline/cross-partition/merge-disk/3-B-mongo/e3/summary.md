# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 17, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/merge/3-B-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 6.5 ms | 21.2 ms | 45.3 ms | 120.8 ms | 190.7 ms | 161.8 ms |

Load generator: 0.24 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.24 | 866 MB | 783 MB | 20 | 0.233 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 42.6% | 159.4% | 1.871GiB / 7.748GiB |
| wow-e2e-bench-mongo | 132.4% | 193.2% | 1.499GiB / 7.748GiB |

Store operations, mongo (seconds -0.642 to 60.622): {epoch=61.26400017738342, insert=127345, query=332, update=158191, delete=0, getmore=0, command=955}
Store operations, kafkaEndOffsets (seconds -30.027 to 62.177): {wow.transfer.account.state=186989}

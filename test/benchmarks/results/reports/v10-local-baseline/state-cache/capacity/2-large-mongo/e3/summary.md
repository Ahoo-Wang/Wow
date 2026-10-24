# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 23, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache-mongo/2-large-mongo/up.

Open loop at 722.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 722.0/s | 43320 | 0 | 6.2 ms | 28.8 ms | 53.8 ms | 179.2 ms | 239.1 ms | 217.1 ms |

Load generator: 0.21 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.32 | 1161 MB | 961 MB | 46 | 0.183 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.3% | 172.5% | 1.914GiB / 7.748GiB |
| wow-e2e-bench-mongo | 265.1% | 408.7% | 1.51GiB / 7.748GiB |

Store operations, mongo (seconds -0.187 to 60.218): {epoch=60.40499997138977, insert=130353, query=30292, update=160464, delete=0, getmore=0, command=922}
Store operations, kafkaEndOffsets (seconds -29.972 to 62.084): {wow.transfer.account.state=191398}

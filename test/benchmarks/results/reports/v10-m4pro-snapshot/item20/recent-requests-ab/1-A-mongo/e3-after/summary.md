# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/rr/ab/1-A-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.7 ms | 15.1 ms | 32.3 ms | 59.4 ms | 82.2 ms | 67.6 ms |

Load generator: 0.20 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.24 | 896 MB | 819 MB | 15 | 0.169 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 56.2% | 179.9% | 1.35GiB / 7.748GiB |
| wow-e2e-bench-mongo | 206.1% | 351.8% | 4.48GiB / 7.748GiB |

Store operations, mongo (seconds -0.442 to 40.831): {epoch=41.27300000190735, insert=84957, query=708, update=105315, delete=0, getmore=45, command=634}
Store operations, kafkaEndOffsets (seconds -20.028 to 41.932): {wow.transfer.account.state=133116}

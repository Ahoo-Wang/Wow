# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 31, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bytes/1-G-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 3.8 ms | 46.8 ms | 121.9 ms | 238.6 ms | 371.6 ms | 313.3 ms |

Load generator: 0.43 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.43 | 921 MB | 802 MB | 23 | 0.214 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 63.6% | 179.2% | 1.81GiB / 7.748GiB |
| wow-e2e-bench-mongo | 268.7% | 490.4% | 1.32GiB / 7.748GiB |

Store operations, mongo (seconds -0.159 to 60.343): {epoch=60.50200009346008, insert=103460, query=36269, update=132593, delete=0, getmore=0, command=5273}
Store operations, kafkaEndOffsets (seconds -29.663 to 62.187): {wow.transfer.account.state=152025}

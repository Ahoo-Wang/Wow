# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 24, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache-mongo/1-large-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.3 ms | 8.4 ms | 20.0 ms | 191.5 ms | 251.3 ms | 235.5 ms |

Load generator: 0.37 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.22 | 1160 MB | 955 MB | 42 | 0.151 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.5% | 179.1% | 1.884GiB / 7.748GiB |
| wow-e2e-bench-mongo | 208.0% | 353.9% | 1.447GiB / 7.748GiB |

Store operations, mongo (seconds -0.169 to 60.14): {epoch=60.30900001525879, insert=103463, query=36260, update=133619, delete=0, getmore=0, command=754}
Store operations, kafkaEndOffsets (seconds -29.751 to 62.027): {wow.transfer.account.state=152529}

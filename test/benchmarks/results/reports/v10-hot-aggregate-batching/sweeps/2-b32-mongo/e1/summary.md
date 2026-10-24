# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 32, settleSeconds 12, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch/2-b32-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.6 ms | 11.8 ms | 31.1 ms | 125.4 ms | 181.7 ms | 170.0 ms |

Load generator: 0.37 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.25 | 916 MB | 818 MB | 24 | 0.156 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 56.2% | 175.5% | 1.794GiB / 7.748GiB |
| wow-e2e-bench-mongo | 217.4% | 297.3% | 1.543GiB / 7.748GiB |

Store operations, mongo (seconds -0.105 to 60.279): {epoch=60.384000062942505, insert=103349, query=11330, update=133498, delete=0, getmore=38, command=8825}
Store operations, kafkaEndOffsets (seconds -29.71 to 62.101): {wow.transfer.account.state=178182}

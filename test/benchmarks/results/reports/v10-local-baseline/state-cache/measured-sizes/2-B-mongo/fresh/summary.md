# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 18, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/2-B-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.1 ms | 8.4 ms | 22.5 ms | 112.6 ms | 153.4 ms | 128.5 ms |

Load generator: 0.35 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.11 | 891 MB | 751 MB | 22 | 0.135 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.9% | 177.5% | 1.703GiB / 7.748GiB |
| wow-e2e-bench-mongo | 193.4% | 264.5% | 1.454GiB / 7.748GiB |

Store operations, mongo (seconds -0.944 to 60.373): {epoch=61.31699991226196, insert=104791, query=324, update=135456, delete=0, getmore=0, command=754}
Store operations, kafkaEndOffsets (seconds -29.73 to 62.04): {wow.transfer.account.state=151863}

# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 33, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/4-A-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.3 ms | 9.5 ms | 26.0 ms | 226.3 ms | 288.6 ms | 260.1 ms |

Load generator: 0.36 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.19 | 939 MB | 800 MB | 22 | 0.128 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.2% | 178.1% | 1.737GiB / 7.748GiB |
| wow-e2e-bench-mongo | 209.1% | 397.5% | 1.44GiB / 7.748GiB |

Store operations, mongo (seconds -0.351 to 60.98): {epoch=61.331000089645386, insert=103780, query=4707, update=133939, delete=0, getmore=0, command=5179}
Store operations, kafkaEndOffsets (seconds -29.786 to 62.066): {wow.transfer.account.state=152114}

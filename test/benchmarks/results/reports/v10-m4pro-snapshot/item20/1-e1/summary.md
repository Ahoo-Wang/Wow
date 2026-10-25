# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 13, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/item20/run/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 0.9 ms | 2.1 ms | 3.5 ms | 7.0 ms | 14.8 ms | 6.9 ms |

Load generator: 0.24 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.85 | 1067 MB | 751 MB | 23 | 0.117 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 37.9% | 134.6% | 1.694GiB / 7.748GiB |
| wow-e2e-bench-mongo | 103.6% | 161.7% | 1.875GiB / 7.748GiB |

Store operations, mongo (seconds -0.409 to 60.804): {epoch=61.21300005912781, insert=103897, query=325, update=135066, delete=0, getmore=0, command=775}
Store operations, kafkaEndOffsets (seconds -29.988 to 61.907): {wow.transfer.account.state=152860}

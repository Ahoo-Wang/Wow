# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 41, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/out/1-A/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 9.7 ms | 46.8 ms | 84.0 ms | 125.4 ms | 206.3 ms | 134.1 ms |

Load generator: 0.39 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.53 | 1137 MB | 817 MB | 45 | 0.293 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 77.2% | 183.3% | 1.704GiB / 7.748GiB |
| wow-e2e-bench-mongo | 410.4% | 628.0% | 1.337GiB / 7.748GiB |

Store operations, mongo (seconds -0.975 to 60.674): {epoch=61.6489999294281, insert=104855, query=77845, update=132465, delete=0, getmore=0, command=78251}
Store operations, kafkaEndOffsets (seconds -29.726 to 62.291): {wow.transfer.account.state=151895}

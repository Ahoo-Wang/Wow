# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 35, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/out/2-B/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 6.0 ms | 27.1 ms | 47.6 ms | 111.6 ms | 173.8 ms | 151.6 ms |

Load generator: 0.46 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.71 | 1107 MB | 858 MB | 48 | 0.248 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 61.5% | 184.2% | 1.917GiB / 7.748GiB |
| wow-e2e-bench-mongo | 347.5% | 550.8% | 1.33GiB / 7.748GiB |

Store operations, mongo (seconds -0.431 to 60.004): {epoch=60.43499994277954, insert=103925, query=320, update=133062, delete=0, getmore=0, command=77568}
Store operations, kafkaEndOffsets (seconds -29.761 to 62.203): {wow.transfer.account.state=152814}

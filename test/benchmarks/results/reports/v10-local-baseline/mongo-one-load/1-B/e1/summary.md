# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 39, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/out/1-B/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 13.3 ms | 64.8 ms | 108.0 ms | 170.0 ms | 229.2 ms | 185.3 ms |

Load generator: 0.45 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.73 | 1221 MB | 865 MB | 45 | 0.326 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 79.8% | 191.7% | 1.979GiB / 7.748GiB |
| wow-e2e-bench-mongo | 436.2% | 659.6% | 1.328GiB / 7.748GiB |

Store operations, mongo (seconds -0.768 to 60.13): {epoch=60.89800000190735, insert=104502, query=323, update=132687, delete=0, getmore=0, command=77950}
Store operations, kafkaEndOffsets (seconds -29.782 to 62.453): {wow.transfer.account.state=151858}

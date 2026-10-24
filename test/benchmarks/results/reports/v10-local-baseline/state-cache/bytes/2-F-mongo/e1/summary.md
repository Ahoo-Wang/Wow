# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 33, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bytes/2-F-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 38.7 ms | 329.7 ms | 557.1 ms | 778.2 ms | 1100.2 ms | 897.0 ms |

Load generator: 0.46 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.63 | 1024 MB | 871 MB | 39 | 0.611 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 99.6% | 245.8% | 1.664GiB / 7.748GiB |
| wow-e2e-bench-mongo | 567.8% | 1105.6% | 1.508GiB / 7.748GiB |

Store operations, mongo (seconds -0.685 to 60.693): {epoch=61.378000020980835, insert=104349, query=77349, update=129907, delete=0, getmore=0, command=77718}
Store operations, kafkaEndOffsets (seconds -29.65 to 62.482): {wow.transfer.account.state=151553}

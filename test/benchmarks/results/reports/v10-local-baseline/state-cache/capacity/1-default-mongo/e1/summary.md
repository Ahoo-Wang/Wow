# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 34, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache-mongo/1-default-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 9.0 ms | 58.4 ms | 130.0 ms | 321.5 ms | 470.0 ms | 372.7 ms |

Load generator: 0.42 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.56 | 1055 MB | 841 MB | 40 | 0.282 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 78.8% | 207.3% | 1.675GiB / 7.748GiB |
| wow-e2e-bench-mongo | 425.4% | 652.3% | 1.449GiB / 7.748GiB |

Store operations, mongo (seconds -0.984 to 60.8): {epoch=61.78399991989136, insert=104869, query=77837, update=132518, delete=0, getmore=0, command=78253}
Store operations, kafkaEndOffsets (seconds -29.643 to 62.25): {wow.transfer.account.state=152244}

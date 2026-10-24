# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 26, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/seq/1-H/up.

Open loop at 722.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 722.0/s | 43320 | 0 | 3.3 ms | 13.1 ms | 31.5 ms | 202.8 ms | 260.3 ms | 240.6 ms |

Load generator: 0.21 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.30 | 976 MB | 836 MB | 25 | 0.182 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 44.7% | 155.2% | 1.99GiB / 7.748GiB |
| wow-e2e-bench-mongo | 177.8% | 284.4% | 1.652GiB / 7.748GiB |

Store operations, mongo (seconds -0.431 to 60.803): {epoch=61.23400020599365, insert=130877, query=333, update=162026, delete=0, getmore=0, command=969}
Store operations, kafkaEndOffsets (seconds -30.029 to 62.09): {wow.transfer.account.state=192578}

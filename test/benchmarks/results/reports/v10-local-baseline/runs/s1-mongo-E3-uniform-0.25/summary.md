# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 36, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-mongo-up.

Open loop at 357.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 357.0/s | 21420 | 0 | 6.8 ms | 426.0 ms | 761.9 ms | 995.3 ms | 1339.3 ms | 1105.9 ms |

Load generator: 0.16 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.01 | 1047 MB | 822 MB | 24 | 0.441 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 56.9% | 235.1% | 1.075GiB / 7.748GiB |
| wow-e2e-bench-mongo | 280.7% | 1100.4% | 2.945GiB / 7.748GiB |

Store operations, mongo (seconds -0.312 to 60.47): {epoch=60.78200006484985, insert=64592, query=35701, update=93430, delete=0, getmore=27, command=32937}
Store operations, kafkaEndOffsets (seconds -29.943 to 62.233): {wow.transfer.account.state=110331}

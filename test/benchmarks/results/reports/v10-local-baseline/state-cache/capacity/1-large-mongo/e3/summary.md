# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 24, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache-mongo/1-large-mongo/up.

Open loop at 722.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 722.0/s | 43320 | 0 | 5.4 ms | 29.4 ms | 73.7 ms | 385.0 ms | 458.2 ms | 423.9 ms |

Load generator: 0.22 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.29 | 1167 MB | 946 MB | 48 | 0.199 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.3% | 175.0% | 1.868GiB / 7.748GiB |
| wow-e2e-bench-mongo | 245.4% | 459.5% | 1.473GiB / 7.748GiB |

Store operations, mongo (seconds -0.475 to 60.897): {epoch=61.371999979019165, insert=131000, query=30449, update=161620, delete=0, getmore=0, command=947}
Store operations, kafkaEndOffsets (seconds -30.024 to 62.076): {wow.transfer.account.state=192373}

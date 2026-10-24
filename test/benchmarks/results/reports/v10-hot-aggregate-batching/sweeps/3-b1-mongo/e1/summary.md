# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 27, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch/3-b1-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.4 ms | 8.4 ms | 20.4 ms | 92.2 ms | 144.4 ms | 135.2 ms |

Load generator: 0.32 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.10 | 980 MB | 842 MB | 24 | 0.155 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 52.1% | 177.9% | 2.006GiB / 7.748GiB |
| wow-e2e-bench-mongo | 179.0% | 301.7% | 1.492GiB / 7.748GiB |

Store operations, mongo (seconds -0.248 to 60.056): {epoch=60.30400013923645, insert=103592, query=7282, update=133748, delete=0, getmore=21, command=7420}
Store operations, kafkaEndOffsets (seconds -29.761 to 62.109): {wow.transfer.account.state=166733}

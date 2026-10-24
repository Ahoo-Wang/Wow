# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 33, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/probe2/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 5.2 ms | 19.7 ms | 35.3 ms | 93.2 ms | 183.1 ms | 135.2 ms |

Load generator: 0.38 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.47 | 1205 MB | 825 MB | 60 | 0.233 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 60.8% | 165.4% | 1.793GiB / 7.748GiB |
| wow-e2e-bench-mongo | 318.2% | 482.7% | 1.314GiB / 7.748GiB |

Store operations, mongo (seconds -0.48 to 59.988): {epoch=60.468000173568726, insert=103969, query=77214, update=133050, delete=0, getmore=0, command=77625}
Store operations, kafkaEndOffsets (seconds -29.591 to 62.091): {wow.transfer.account.state=150348}

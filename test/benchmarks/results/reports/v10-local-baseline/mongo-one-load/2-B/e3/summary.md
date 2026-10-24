# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 35, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/out/2-B/up.

Open loop at 714.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 714.0/s | 42840 | 0 | 11.8 ms | 38.9 ms | 64.5 ms | 192.5 ms | 246.8 ms | 223.2 ms |

Load generator: 0.21 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.45 | 1111 MB | 827 MB | 45 | 0.227 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 62.8% | 181.5% | 2.016GiB / 7.748GiB |
| wow-e2e-bench-mongo | 363.5% | 485.1% | 1.536GiB / 7.748GiB |

Store operations, mongo (seconds -0.244 to 60.212): {epoch=60.45599985122681, insert=129039, query=321, update=158647, delete=0, getmore=0, command=64436}
Store operations, kafkaEndOffsets (seconds -30.023 to 62.189): {wow.transfer.account.state=190503}

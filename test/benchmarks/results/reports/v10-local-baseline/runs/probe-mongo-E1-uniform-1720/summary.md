# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 32, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/probe/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 4.5 ms | 17.0 ms | 36.9 ms | 144.4 ms | 207.3 ms | 185.3 ms |

Load generator: 0.37 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.58 | 1320 MB | 801 MB | 65 | 0.240 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 58.9% | 169.3% | 1.759GiB / 7.748GiB |
| wow-e2e-bench-mongo | 293.4% | 412.3% | 1.591GiB / 7.748GiB |

Store operations, mongo (seconds -0.29 to 60.043): {epoch=60.33300018310547, insert=103669, query=76971, update=133301, delete=0, getmore=0, command=77387}
Store operations, kafkaEndOffsets (seconds -29.699 to 62.091): {wow.transfer.account.state=151358}

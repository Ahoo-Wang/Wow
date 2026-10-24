# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 30, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/seq/2-H/up.

Open loop at 722.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 722.0/s | 43320 | 0 | 4.5 ms | 17.5 ms | 42.5 ms | 241.7 ms | 292.5 ms | 280.6 ms |

Load generator: 0.21 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.33 | 967 MB | 839 MB | 24 | 0.163 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.9% | 170.9% | 1.817GiB / 7.748GiB |
| wow-e2e-bench-mongo | 211.8% | 320.6% | 1.51GiB / 7.748GiB |

Store operations, mongo (seconds -0.393 to 60.91): {epoch=61.30299997329712, insert=130797, query=334, update=161432, delete=0, getmore=0, command=971}
Store operations, kafkaEndOffsets (seconds -30.0 to 62.07): {wow.transfer.account.state=192353}

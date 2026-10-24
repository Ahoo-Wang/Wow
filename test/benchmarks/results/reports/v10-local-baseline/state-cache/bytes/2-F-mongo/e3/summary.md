# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 33, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bytes/2-F-mongo/up.

Open loop at 722.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 722.0/s | 43320 | 0 | 145.4 ms | 630.8 ms | 901.1 ms | 1220.6 ms | 1477.0 ms | 1351.7 ms |

Load generator: 0.28 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.60 | 1041 MB | 847 MB | 36 | 0.583 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 102.8% | 233.5% | 1.788GiB / 7.748GiB |
| wow-e2e-bench-mongo | 620.0% | 1011.9% | 1.586GiB / 7.748GiB |

Store operations, mongo (seconds -0.993 to 60.928): {epoch=61.9210000038147, insert=132062, query=65197, update=157931, delete=0, getmore=0, command=65742}
Store operations, kafkaEndOffsets (seconds -30.009 to 61.603): {wow.transfer.account.state=189994}

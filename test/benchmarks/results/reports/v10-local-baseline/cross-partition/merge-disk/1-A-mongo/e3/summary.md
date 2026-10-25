# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/merge/1-A-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 6.1 ms | 30.2 ms | 63.7 ms | 201.7 ms | 262.7 ms | 237.6 ms |

Load generator: 0.22 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.26 | 903 MB | 734 MB | 33 | 0.167 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 61.2% | 182.8% | 2.055GiB / 7.748GiB |
| wow-e2e-bench-mongo | 250.6% | 389.5% | 1.79GiB / 7.748GiB |

Store operations, mongo (seconds -0.502 to 60.867): {epoch=61.36899995803833, insert=127016, query=326, update=156766, delete=0, getmore=0, command=930}
Store operations, kafkaEndOffsets (seconds -30.022 to 62.135): {wow.transfer.account.state=186564}

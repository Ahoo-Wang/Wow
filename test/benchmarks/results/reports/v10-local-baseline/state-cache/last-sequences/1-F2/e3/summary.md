# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 33, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/seq/1-F2/up.

Open loop at 722.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 722.0/s | 43320 | 0 | 4.2 ms | 15.7 ms | 37.1 ms | 158.7 ms | 205.3 ms | 197.6 ms |

Load generator: 0.20 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.23 | 976 MB | 840 MB | 25 | 0.170 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 47.1% | 170.2% | 1.942GiB / 7.748GiB |
| wow-e2e-bench-mongo | 200.5% | 321.1% | 1.51GiB / 7.748GiB |

Store operations, mongo (seconds -0.307 to 60.967): {epoch=61.2739999294281, insert=130612, query=30361, update=161755, delete=0, getmore=0, command=976}
Store operations, kafkaEndOffsets (seconds -30.01 to 62.008): {wow.transfer.account.state=192790}

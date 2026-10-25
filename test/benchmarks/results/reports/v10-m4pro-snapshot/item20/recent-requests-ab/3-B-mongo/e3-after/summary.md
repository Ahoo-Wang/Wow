# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 15, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/rr/ab/3-B-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.3 ms | 8.9 ms | 22.3 ms | 54.0 ms | 72.9 ms | 62.7 ms |

Load generator: 0.18 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.30 | 951 MB | 816 MB | 17 | 0.156 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.1% | 160.5% | 1.035GiB / 7.748GiB |
| wow-e2e-bench-mongo | 154.3% | 233.9% | 4.394GiB / 7.748GiB |

Store operations, mongo (seconds -0.312 to 40.858): {epoch=41.170000076293945, insert=84649, query=756, update=104987, delete=0, getmore=45, command=660}
Store operations, kafkaEndOffsets (seconds -20.026 to 41.938): {wow.transfer.account.state=132945}

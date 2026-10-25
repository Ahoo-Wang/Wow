# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 19, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers/6-A-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 5.1 ms | 20.0 ms | 44.5 ms | 208.9 ms | 264.9 ms | 248.8 ms |

Load generator: 0.22 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.19 | 876 MB | 777 MB | 23 | 0.161 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.7% | 183.7% | 1.956GiB / 7.748GiB |
| wow-e2e-bench-mongo | 231.6% | 368.1% | 1.668GiB / 7.748GiB |

Store operations, mongo (seconds -0.305 to 60.051): {epoch=60.35599994659424, insert=126619, query=321, update=156356, delete=0, getmore=0, command=922}
Store operations, kafkaEndOffsets (seconds -30.029 to 62.092): {wow.transfer.account.state=187162}

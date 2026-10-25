# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers/1-A-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 15.7 ms | 77.8 ms | 118.3 ms | 169.0 ms | 206.6 ms | 178.2 ms |

Load generator: 0.36 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.33 | 866 MB | 803 MB | 22 | 0.387 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 76.2% | 196.6% | 1.868GiB / 7.748GiB |
| wow-e2e-bench-mongo | 338.8% | 544.5% | 1.74GiB / 7.748GiB |

Store operations, mongo (seconds -0.68 to 60.031): {epoch=60.710999965667725, insert=127327, query=323, update=155817, delete=0, getmore=0, command=878}
Store operations, kafkaEndOffsets (seconds -29.825 to 62.426): {wow.transfer.account.state=185059}

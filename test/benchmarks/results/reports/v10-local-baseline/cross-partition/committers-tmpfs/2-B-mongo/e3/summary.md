# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 15, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers-tmpfs/2-B-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 4.3 ms | 17.7 ms | 97.8 ms | 331.8 ms | 402.7 ms | 372.7 ms |

Load generator: 0.23 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.13 | 877 MB | 780 MB | 22 | 0.280 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.0% | 169.5% | 2.085GiB / 7.748GiB |
| wow-e2e-bench-mongo | 152.5% | 444.8% | 2.749GiB / 7.748GiB |

Store operations, mongo (seconds -0.504 to 60.883): {epoch=61.38700008392334, insert=127055, query=328, update=157816, delete=0, getmore=0, command=951}
Store operations, kafkaEndOffsets (seconds -30.021 to 62.115): {wow.transfer.account.state=187385}

# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 36, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-mongo-up.

Open loop at 714.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 714.0/s | 42840 | 0 | 20.4 ms | 77.8 ms | 153.6 ms | 288.8 ms | 398.7 ms | 331.8 ms |

Load generator: 0.22 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.42 | 1087 MB | 753 MB | 45 | 0.305 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 64.9% | 190.7% | 1.824GiB / 7.748GiB |
| wow-e2e-bench-mongo | 493.9% | 664.8% | 3.141GiB / 7.748GiB |

Store operations, mongo (seconds -0.299 to 60.326): {epoch=60.625, insert=129150, query=63878, update=157868, delete=0, getmore=0, command=64889}
Store operations, kafkaEndOffsets (seconds -29.99 to 62.178): {wow.transfer.account.state=188303}

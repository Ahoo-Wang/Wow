# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 13, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers-tmpfs/4-C-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 3.0 ms | 10.6 ms | 23.6 ms | 104.4 ms | 155.0 ms | 136.2 ms |

Load generator: 0.20 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.26 | 957 MB | 770 MB | 24 | 0.171 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 52.6% | 179.8% | 2.148GiB / 7.748GiB |
| wow-e2e-bench-mongo | 178.6% | 308.1% | 2.768GiB / 7.748GiB |

Store operations, mongo (seconds -0.661 to 60.633): {epoch=61.293999910354614, insert=127375, query=326, update=158135, delete=0, getmore=0, command=937}
Store operations, kafkaEndOffsets (seconds -30.036 to 62.078): {wow.transfer.account.state=187534}

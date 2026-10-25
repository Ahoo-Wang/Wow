# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 17, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers-tmpfs/1-A-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 2.5 ms | 8.8 ms | 24.3 ms | 141.3 ms | 236.8 ms | 189.4 ms |

Load generator: 0.20 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.31 | 937 MB | 740 MB | 40 | 0.240 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 48.4% | 173.0% | 1.983GiB / 7.748GiB |
| wow-e2e-bench-mongo | 190.5% | 406.5% | 2.792GiB / 7.748GiB |

Store operations, mongo (seconds -0.327 to 60.988): {epoch=61.31500005722046, insert=126688, query=324, update=157187, delete=0, getmore=0, command=991}
Store operations, kafkaEndOffsets (seconds -30.022 to 62.106): {wow.transfer.account.state=187239}

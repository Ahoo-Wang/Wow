# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 15, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/rr/ab/3-B-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 68800 | 0 | 1.5 ms | 25.9 ms | 88.6 ms | 152.6 ms | 197.6 ms | 177.2 ms |

Load generator: 0.43 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.19 | 1102 MB | 818 MB | 21 | 0.155 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.2% | 192.9% | 1009MiB / 7.748GiB |
| wow-e2e-bench-mongo | 201.7% | 569.5% | 4.498GiB / 7.748GiB |

Store operations, mongo (seconds -0.867 to 40.402): {epoch=41.26899981498718, insert=70261, query=11779, update=90909, delete=0, getmore=168, command=580}
Store operations, kafkaEndOffsets (seconds -19.732 to 41.915): {wow.transfer.account.state=127652}

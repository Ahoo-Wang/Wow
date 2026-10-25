# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers/3-C-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 699.2/s | 41952 | 48 | 4.7 ms | 38.4 ms | 1097.7 ms | 1646.6 ms | 1823.2 ms | 1769.5 ms |

Errors: {exception-SocketException=48}

Load generator: 0.24 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.14 | 925 MB | 775 MB | 23 | 0.301 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.9% | 163.0% | 2.071GiB / 7.748GiB |
| wow-e2e-bench-mongo | 211.4% | 862.5% | 1.53GiB / 7.748GiB |

Store operations, mongo (seconds -0.496 to 60.232): {epoch=60.7279999256134, insert=126885, query=322, update=156624, delete=0, getmore=0, command=1013}
Store operations, kafkaEndOffsets (seconds -30.007 to 61.662): {wow.transfer.account.state=186946}

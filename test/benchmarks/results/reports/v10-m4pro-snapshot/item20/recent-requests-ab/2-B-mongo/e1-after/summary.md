# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 15, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/rr/ab/2-B-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 68800 | 0 | 1.3 ms | 4.8 ms | 12.3 ms | 29.6 ms | 55.7 ms | 35.8 ms |

Load generator: 0.31 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.10 | 1043 MB | 828 MB | 17 | 0.135 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 47.6% | 165.3% | 1.034GiB / 7.748GiB |
| wow-e2e-bench-mongo | 158.8% | 346.2% | 4.555GiB / 7.748GiB |

Store operations, mongo (seconds -0.172 to 40.052): {epoch=40.223999977111816, insert=69068, query=748, update=89194, delete=0, getmore=43, command=524}
Store operations, kafkaEndOffsets (seconds -19.724 to 41.844): {wow.transfer.account.state=123973}

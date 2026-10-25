# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 13, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/sharding-ab/3-B-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 0.9 ms | 2.1 ms | 3.7 ms | 7.9 ms | 24.0 ms | 15.7 ms |

Load generator: 0.25 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.89 | 1069 MB | 749 MB | 26 | 0.140 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 39.1% | 151.9% | 1.712GiB / 7.748GiB |
| wow-e2e-bench-mongo | 104.7% | 162.5% | 1.891GiB / 7.748GiB |

Store operations, mongo (seconds -0.462 to 60.748): {epoch=61.21000003814697, insert=103987, query=324, update=135158, delete=0, getmore=0, command=773}
Store operations, kafkaEndOffsets (seconds -29.828 to 62.036): {wow.transfer.account.state=152850}

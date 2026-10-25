# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 30, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/1-A-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.2 ms | 10.8 ms | 46.1 ms | 108.5 ms | 180.0 ms | 136.2 ms |

Load generator: 0.37 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.16 | 953 MB | 795 MB | 23 | 0.172 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 54.9% | 204.8% | 1.812GiB / 7.748GiB |
| wow-e2e-bench-mongo | 202.0% | 445.4% | 1.601GiB / 7.748GiB |

Store operations, mongo (seconds -0.215 to 60.13): {epoch=60.34499979019165, insert=103556, query=4688, update=133193, delete=0, getmore=0, command=5120}
Store operations, kafkaEndOffsets (seconds -29.772 to 62.06): {wow.transfer.account.state=152572}

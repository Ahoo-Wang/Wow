# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers/3-C-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.5 ms | 10.0 ms | 29.8 ms | 99.8 ms | 147.6 ms | 124.9 ms |

Load generator: 0.37 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.11 | 931 MB | 746 MB | 22 | 0.148 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 55.6% | 186.6% | 1.7GiB / 7.748GiB |
| wow-e2e-bench-mongo | 196.5% | 294.5% | 1.466GiB / 7.748GiB |

Store operations, mongo (seconds -0.372 to 60.981): {epoch=61.3529999256134, insert=103827, query=326, update=133976, delete=0, getmore=0, command=787}
Store operations, kafkaEndOffsets (seconds -29.775 to 62.062): {wow.transfer.account.state=152959}

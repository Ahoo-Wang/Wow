# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 7, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers/4-C-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.0 ms | 6.7 ms | 15.7 ms | 146.4 ms | 212.2 ms | 195.6 ms |

Load generator: 0.35 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.08 | 984 MB | 739 MB | 22 | 0.121 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 50.1% | 186.8% | 1.744GiB / 7.748GiB |
| wow-e2e-bench-mongo | 173.9% | 251.6% | 1.293GiB / 7.748GiB |

Store operations, mongo (seconds -0.36 to 60.956): {epoch=61.31599998474121, insert=103806, query=329, update=134465, delete=0, getmore=0, command=775}
Store operations, kafkaEndOffsets (seconds -29.828 to 62.029): {wow.transfer.account.state=152535}

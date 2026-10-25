# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/merge/1-A-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.9 ms | 9.9 ms | 51.7 ms | 236.5 ms | 342.6 ms | 280.6 ms |

Load generator: 0.34 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.11 | 976 MB | 732 MB | 38 | 0.187 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 47.0% | 163.9% | 1.707GiB / 7.748GiB |
| wow-e2e-bench-mongo | 174.7% | 372.2% | 1.579GiB / 7.748GiB |

Store operations, mongo (seconds -0.442 to 60.839): {epoch=61.28099989891052, insert=103949, query=325, update=134606, delete=0, getmore=0, command=764}
Store operations, kafkaEndOffsets (seconds -29.722 to 62.079): {wow.transfer.account.state=152415}

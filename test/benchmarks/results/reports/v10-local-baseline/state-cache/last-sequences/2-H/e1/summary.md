# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 30, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/seq/2-H/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.4 ms | 11.5 ms | 30.8 ms | 129.5 ms | 184.7 ms | 176.1 ms |

Load generator: 0.38 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.21 | 912 MB | 807 MB | 22 | 0.164 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 54.9% | 191.2% | 1.78GiB / 7.748GiB |
| wow-e2e-bench-mongo | 199.6% | 382.9% | 1.299GiB / 7.748GiB |

Store operations, mongo (seconds -0.955 to 60.426): {epoch=61.38100004196167, insert=104809, query=4865, update=135065, delete=0, getmore=0, command=5300}
Store operations, kafkaEndOffsets (seconds -29.744 to 62.066): {wow.transfer.account.state=151677}

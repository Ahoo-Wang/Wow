# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 17, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/merge/3-B-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 4.8 ms | 36.6 ms | 89.6 ms | 233.5 ms | 276.0 ms | 266.2 ms |

Load generator: 0.39 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.02 | 888 MB | 749 MB | 19 | 0.189 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 55.6% | 189.9% | 1.694GiB / 7.748GiB |
| wow-e2e-bench-mongo | 158.0% | 318.3% | 1.293GiB / 7.748GiB |

Store operations, mongo (seconds -0.444 to 60.929): {epoch=61.37299990653992, insert=103955, query=325, update=134106, delete=0, getmore=0, command=775}
Store operations, kafkaEndOffsets (seconds -29.818 to 62.208): {wow.transfer.account.state=152828}

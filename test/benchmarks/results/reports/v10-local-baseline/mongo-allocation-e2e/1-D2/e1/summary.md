# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 32, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/out/1-D2/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 4.1 ms | 11.3 ms | 25.1 ms | 152.6 ms | 212.6 ms | 195.6 ms |

Load generator: 0.38 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.52 | 1126 MB | 836 MB | 42 | 0.196 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 56.6% | 167.9% | 1.981GiB / 7.748GiB |
| wow-e2e-bench-mongo | 275.9% | 379.4% | 1.322GiB / 7.748GiB |

Store operations, mongo (seconds -0.48 to 60.855): {epoch=61.33500003814697, insert=104015, query=77239, update=134176, delete=0, getmore=0, command=77675}
Store operations, kafkaEndOffsets (seconds -29.795 to 62.142): {wow.transfer.account.state=152857}

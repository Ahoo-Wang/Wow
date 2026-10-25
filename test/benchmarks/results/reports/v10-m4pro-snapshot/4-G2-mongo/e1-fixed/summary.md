# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261025-001403-run-mongo-n1-saturation.

Open loop at 7502.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7502.0/s | 450120 | 0 | 10.9 ms | 69.6 ms | 225.3 ms | 372.7 ms | 466.1 ms | 411.6 ms |

Load generator: 1.82 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.04 | 1677 MB | 1557 MB | 33 | 0.293 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 44.3% | 151.4% | 2.045GiB / 15.6GiB |
| wow-e2e-bench-mongo | 273.1% | 373.4% | 2.36GiB / 15.6GiB |

Store operations, mongo (seconds -0.522 to 60.785): {epoch=61.306999921798706, insert=457119, query=326, update=489368, delete=0, getmore=0, command=2167}
Store operations, kafkaEndOffsets (seconds -29.641 to 62.113): {wow.transfer.account.state=657601}

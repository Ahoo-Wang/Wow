# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 26, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch/2-b1-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.2 ms | 9.0 ms | 21.0 ms | 64.3 ms | 116.7 ms | 109.6 ms |

Load generator: 0.37 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.19 | 978 MB | 837 MB | 23 | 0.145 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 54.1% | 178.9% | 2.081GiB / 7.748GiB |
| wow-e2e-bench-mongo | 202.9% | 320.3% | 1.374GiB / 7.748GiB |

Store operations, mongo (seconds -0.412 to 60.913): {epoch=61.325000047683716, insert=103892, query=7038, update=134579, delete=0, getmore=23, command=7134}
Store operations, kafkaEndOffsets (seconds -29.775 to 62.049): {wow.transfer.account.state=166407}

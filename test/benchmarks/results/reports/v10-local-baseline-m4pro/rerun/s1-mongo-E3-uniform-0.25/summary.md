# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s1-mongo-up.

Open loop at 456.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 456.0/s | 27360 | 0 | 7.8 ms | 557.1 ms | 2916.4 ms | 3391.5 ms | 3586.6 ms | 3555.3 ms |

Load generator: 0.15 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.16 | 1255 MB | 921 MB | 54 | 0.451 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 35.9% | 150.7% | 2.17GiB / 15.6GiB |
| wow-e2e-bench-mongo | 256.9% | 821.7% | 3.569GiB / 15.6GiB |

Store operations, mongo (seconds -0.402 to 60.847): {epoch=61.24899983406067, insert=82625, query=45132, update=111658, delete=0, getmore=273, command=32415}
Store operations, kafkaEndOffsets (seconds -29.994 to 62.125): {wow.transfer.account.state=157410}

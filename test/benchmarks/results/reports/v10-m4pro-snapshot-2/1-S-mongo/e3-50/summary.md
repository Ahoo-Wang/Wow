# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-094252-run-mongo-n1-saturation.

Open loop at 2890.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2643.6/s | 158616 | 14784 | 51.5 ms | 1359.9 ms | 4489.2 ms | 4948.0 ms | 6845.8 ms | 5111.8 ms |

Errors: {http-429-wow.command.busy=14784}

Load generator: 0.65 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.55 | 1151 MB | 807 MB | 69 | 0.466 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.5% | 146.4% | 2.274GiB / 15.6GiB |
| wow-e2e-bench-mongo | 291.8% | 573.7% | 3.912GiB / 15.6GiB |

Store operations, mongo (seconds -0.52 to 60.88): {epoch=61.40000009536743, insert=480993, query=3220, update=509545, delete=0, getmore=167, command=3051}
Store operations, kafkaEndOffsets (seconds -30.022 to 62.265): {wow.transfer.account.state=727266}

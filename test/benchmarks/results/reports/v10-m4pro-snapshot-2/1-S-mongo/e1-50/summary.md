# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-094252-run-mongo-n1-saturation.

Open loop at 8108.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7808.8/s | 468525 | 17955 | 23.0 ms | 234.5 ms | 581.6 ms | 2768.9 ms | 2940.1 ms | 2867.2 ms |

Errors: {http-429-wow.command.busy=17955}

Load generator: 1.84 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.99 | 1096 MB | 829 MB | 77 | 0.496 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 52.6% | 184.3% | 2.172GiB / 15.6GiB |
| wow-e2e-bench-mongo | 333.3% | 543.1% | 3.26GiB / 15.6GiB |

Store operations, mongo (seconds -0.031 to 60.373): {epoch=60.40400004386902, insert=468964, query=1201, update=499218, delete=0, getmore=165, command=2421}
Store operations, kafkaEndOffsets (seconds -29.981 to 62.31): {wow.transfer.account.state=689446}

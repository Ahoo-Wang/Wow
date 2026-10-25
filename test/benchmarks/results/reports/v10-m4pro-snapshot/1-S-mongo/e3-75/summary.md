# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-231946-run-mongo-n1-saturation.

Open loop at 2377.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1709.2/s | 102552 | 40068 | 1490.9 ms | 2506.8 ms | 3162.1 ms | 4161.5 ms | 6746.8 ms | 5341.2 ms |

Errors: {http-429-wow.command.busy=40065, http-504-wow.wait.timeout=3}

Load generator: 0.46 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.66 | 1236 MB | 878 MB | 133 | 2.151 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 60.8% | 186.9% | 2.172GiB / 15.6GiB |
| wow-e2e-bench-mongo | 884.9% | 999.8% | 4.531GiB / 15.6GiB |

Store operations, mongo (seconds -0.197 to 60.401): {epoch=60.598000049591064, insert=313267, query=94273, update=340542, delete=0, getmore=96, command=94356}
Store operations, kafkaEndOffsets (seconds -30.032 to 62.017): {wow.transfer.account.state=470037}

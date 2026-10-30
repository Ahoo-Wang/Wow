# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-053401-run-mongo-n1-saturation.

Open loop at 17059.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 16840.1/s | 1010403 | 13137 | 49.2 ms | 103.9 ms | 158.7 ms | 337.9 ms | 457.5 ms | 409.6 ms |

Errors: {generator-overload=13137}

Load generator: 2.43 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.43 | 1599 MB | 838 MB | 168 | 0.681 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.3% | 152.3% | 1.146GiB / 31.36GiB |
| wow-e2e-bench-mongo | 509.5% | 591.4% | 3.053GiB / 31.36GiB |

Store operations, mongo (seconds -0.632 to 60.687): {epoch=61.319000005722046, insert=1021569, query=1180, update=1056176, delete=0, getmore=219, command=5060}
Store operations, kafkaEndOffsets (seconds -30.039 to 62.034): {wow.transfer.account.state=1488819, wow.transfer.commands=0}

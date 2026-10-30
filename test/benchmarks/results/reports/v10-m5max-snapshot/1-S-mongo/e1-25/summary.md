# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-053401-run-mongo-n1-saturation.

Open loop at 5686.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5686.0/s | 341160 | 0 | 5.8 ms | 17.3 ms | 127.5 ms | 290.8 ms | 373.7 ms | 342.0 ms |

Load generator: 1.23 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.96 | 1599 MB | 802 MB | 100 | 0.206 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 38.2% | 134.3% | 1.144GiB / 31.36GiB |
| wow-e2e-bench-mongo | 221.8% | 333.3% | 2.798GiB / 31.36GiB |

Store operations, mongo (seconds -0.74 to 60.509): {epoch=61.24900007247925, insert=345451, query=1145, update=377091, delete=0, getmore=182, command=2283}
Store operations, kafkaEndOffsets (seconds -29.838 to 61.964): {wow.transfer.account.state=515583, wow.transfer.commands=0}

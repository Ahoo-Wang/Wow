# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 10, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-232013-run-mongo-n1-saturation.

Open loop at 7017.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6812.3/s | 408740 | 12280 | 12.2 ms | 47.6 ms | 1474.6 ms | 2408.4 ms | 2594.3 ms | 2506.8 ms |

Errors: {http-429-wow.command.busy=12280}

Load generator: 1.76 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.28 | 1466 MB | 837 MB | 64 | 0.309 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 50.2% | 176.1% | 2.035GiB / 15.6GiB |
| wow-e2e-bench-mongo | 307.2% | 428.0% | 2.398GiB / 15.6GiB |

Store operations, mongo (seconds -0.518 to 60.731): {epoch=61.24900007247925, insert=412366, query=325, update=443245, delete=0, getmore=0, command=2121}
Store operations, kafkaEndOffsets (seconds -29.836 to 62.098): {wow.transfer.account.state=612877}

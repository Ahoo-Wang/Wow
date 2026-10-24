# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 36, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-mongo-up.

Open loop at 1070.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 968.9/s | 58132 | 6068 | 1818.6 ms | 4849.7 ms | 6324.2 ms | 8716.3 ms | 9996.9 ms | 8978.4 ms |

Errors: {http-429-wow.command.busy=6021, http-504-wow.wait.timeout=47}

Load generator: 0.31 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.06 | 1294 MB | 906 MB | 116 | 2.598 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 88.8% | 208.1% | 1.912GiB / 7.748GiB |
| wow-e2e-bench-mongo | 823.0% | 1170.8% | 4.144GiB / 7.748GiB |

Store operations, mongo (seconds -0.357 to 60.25): {epoch=60.60699987411499, insert=172275, query=193265, update=103832, delete=0, getmore=13, command=85549}
Store operations, kafkaEndOffsets (seconds -30.024 to 62.701): {wow.transfer.account.state=268098}

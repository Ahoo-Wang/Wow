# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 14, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-mongo-up.

Open loop at 2412.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1449.4/s | 86964 | 57756 | 1826.8 ms | 3719.2 ms | 5603.3 ms | 8388.6 ms | 9980.8 ms | 9175.0 ms |

Errors: {exception-SocketException=246, http-429-wow.command.busy=56998, http-504-wow.wait.timeout=512}

Load generator: 0.43 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.74 | 1238 MB | 950 MB | 311 | 5.181 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 64.6% | 189.3% | 2.168GiB / 15.6GiB |
| wow-e2e-bench-mongo | 881.6% | 1011.1% | 4.114GiB / 15.6GiB |

Store operations, mongo (seconds -0.277 to 60.334): {epoch=60.611000061035156, insert=276145, query=361867, update=127189, delete=0, getmore=328, command=86175}
Store operations, kafkaEndOffsets (seconds -30.036 to 67.153): {wow.transfer.account.state=458754}

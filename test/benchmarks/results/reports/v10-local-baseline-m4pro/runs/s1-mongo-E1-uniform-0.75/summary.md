# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 3, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-mongo-up.

Open loop at 9415.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4868.5/s | 292107 | 272793 | 741.4 ms | 1818.6 ms | 2457.6 ms | 3326.0 ms | 5241.8 ms | 2981.9 ms |

Errors: {exception-SocketException=531, http-429-wow.command.busy=272262}

Load generator: 1.12 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.43 | 1282 MB | 924 MB | 415 | 6.708 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 68.3% | 189.8% | 2.126GiB / 15.6GiB |
| wow-e2e-bench-mongo | 685.5% | 777.4% | 2.274GiB / 15.6GiB |

Store operations, mongo (seconds -0.517 to 60.041): {epoch=60.55800008773804, insert=295158, query=509336, update=223989, delete=0, getmore=346, command=72452}
Store operations, kafkaEndOffsets (seconds -30.041 to 61.852): {wow.transfer.account.state=514337}

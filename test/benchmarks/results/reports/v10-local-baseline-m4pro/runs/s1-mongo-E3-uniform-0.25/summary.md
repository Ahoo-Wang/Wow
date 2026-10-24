# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 5, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-mongo-up.

Open loop at 804.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 752.5/s | 45149 | 3090 | 10.9 ms | 6357.0 ms | 8781.8 ms | 9830.4 ms | 9998.1 ms | 9961.5 ms |

Errors: {http-429-wow.command.busy=2422, http-504-wow.wait.timeout=668}

Load generator: 0.23 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.38 | 1285 MB | 876 MB | 47 | 0.501 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 45.0% | 184.3% | 2.193GiB / 15.6GiB |
| wow-e2e-bench-mongo | 394.3% | 1068.6% | 3.35GiB / 15.6GiB |

Store operations, mongo (seconds -0.344 to 60.334): {epoch=60.67799997329712, insert=136895, query=67073, update=159720, delete=0, getmore=79, command=64974}
Store operations, kafkaEndOffsets (seconds -29.996 to 67.683): {wow.transfer.account.state=220938}

# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 9, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-231946-run-mongo-n1-saturation.

Open loop at 11750.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 763.8/s | 45826 | 659174 | 5767.2 ms | 9895.9 ms | 11468.8 ms | 12320.8 ms | 19264.8 ms | 19264.8 ms |

Errors: {exception-BindException=16101, exception-ConnectException=9, exception-IOException=43, generator-overload=233868, http-429-wow.command.busy=406097, http-504-wow.wait.timeout=3056}

Load generator: 2.01 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.41 | 1245 MB | 923 MB | 82 | 2.369 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 90.3% | 230.5% | 2.168GiB / 15.6GiB |
| wow-e2e-bench-mongo | 831.2% | 1252.8% | 3.88GiB / 15.6GiB |

Store operations, mongo (seconds -0.464 to 60.117): {epoch=60.581000089645386, insert=47986, query=112322, update=30434, delete=1, getmore=33, command=48772}
Store operations, kafkaEndOffsets (seconds -30.023 to 62.924): {wow.transfer.account.state=32444}

# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 18, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/3-B-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.0 ms | 6.6 ms | 15.1 ms | 76.3 ms | 143.1 ms | 115.2 ms |

Load generator: 0.34 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.13 | 929 MB | 755 MB | 22 | 0.122 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 52.3% | 172.0% | 1.811GiB / 7.748GiB |
| wow-e2e-bench-mongo | 184.8% | 343.2% | 1.617GiB / 7.748GiB |

Store operations, mongo (seconds -0.358 to 60.946): {epoch=61.30399990081787, insert=103801, query=332, update=133945, delete=0, getmore=0, command=777}
Store operations, kafkaEndOffsets (seconds -29.786 to 62.04): {wow.transfer.account.state=152242}

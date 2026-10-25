# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 13, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/item20/run/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.6 ms | 12.1 ms | 37.1 ms | 185.3 ms | 267.1 ms | 236.5 ms |

Load generator: 0.34 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.04 | 860 MB | 765 MB | 22 | 0.323 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.9% | 185.3% | 1.963GiB / 7.748GiB |
| wow-e2e-bench-mongo | 205.1% | 471.6% | 3.86GiB / 7.748GiB |

Store operations, mongo (seconds -0.359 to 60.062): {epoch=60.4210000038147, insert=103805, query=320, update=133964, delete=0, getmore=0, command=833}
Store operations, kafkaEndOffsets (seconds -29.953 to 62.262): {wow.transfer.account.state=153030}

# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 15, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers-tmpfs/2-B-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.6 ms | 4.5 ms | 8.0 ms | 76.8 ms | 141.9 ms | 127.0 ms |

Load generator: 0.32 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.95 | 918 MB | 753 MB | 21 | 0.115 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 47.1% | 170.8% | 1.902GiB / 7.748GiB |
| wow-e2e-bench-mongo | 123.4% | 196.8% | 2.021GiB / 7.748GiB |

Store operations, mongo (seconds -0.441 to 60.84): {epoch=61.2810001373291, insert=103883, query=328, update=134540, delete=0, getmore=0, command=775}
Store operations, kafkaEndOffsets (seconds -29.856 to 62.056): {wow.transfer.account.state=152817}

# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 51, settleSeconds 11, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch/1-b32-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 8.9 ms | 51.7 ms | 110.1 ms | 218.1 ms | 360.6 ms | 292.9 ms |

Load generator: 0.47 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.54 | 961 MB | 849 MB | 22 | 0.269 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 84.8% | 213.2% | 1.895GiB / 7.748GiB |
| wow-e2e-bench-mongo | 326.4% | 587.3% | 1.53GiB / 7.748GiB |

Store operations, mongo (seconds -0.565 to 60.163): {epoch=60.7279999256134, insert=104118, query=9135, update=132236, delete=0, getmore=21, command=7650}
Store operations, kafkaEndOffsets (seconds -29.676 to 62.464): {wow.transfer.account.state=168398}

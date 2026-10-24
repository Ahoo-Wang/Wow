# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 36, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-mongo-up.

Open loop at 770.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 770.0/s | 46200 | 0 | 1.4 ms | 2.6 ms | 4.8 ms | 52.7 ms | 92.8 ms | 84.0 ms |

Load generator: 0.20 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.86 | 1082 MB | 819 MB | 13 | 0.102 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 26.6% | 95.4% | 1.844GiB / 7.748GiB |
| wow-e2e-bench-mongo | 52.0% | 79.7% | 2.316GiB / 7.748GiB |

Store operations, mongo (seconds -0.566 to 60.605): {epoch=61.1710000038147, insert=46632, query=4901, update=77052, delete=0, getmore=24, command=4856}
Store operations, kafkaEndOffsets (seconds -29.996 to 62.252): {wow.transfer.account.state=76879}

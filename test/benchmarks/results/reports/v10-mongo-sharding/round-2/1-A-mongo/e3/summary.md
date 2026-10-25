# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 16, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/sharding-ab/1-A-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 2.1 ms | 6.0 ms | 11.2 ms | 21.0 ms | 39.3 ms | 28.2 ms |

Load generator: 0.17 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.11 | 906 MB | 775 MB | 24 | 0.166 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 38.0% | 147.6% | 1.875GiB / 7.748GiB |
| wow-e2e-bench-mongo | 127.2% | 223.8% | 2.759GiB / 7.748GiB |

Store operations, mongo (seconds -0.522 to 60.694): {epoch=61.21600008010864, insert=127088, query=324, update=158360, delete=0, getmore=0, command=955}
Store operations, kafkaEndOffsets (seconds -30.018 to 62.08): {wow.transfer.account.state=187309}

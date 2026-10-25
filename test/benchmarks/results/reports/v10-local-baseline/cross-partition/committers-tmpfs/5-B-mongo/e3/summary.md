# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 15, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers-tmpfs/5-B-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 3.2 ms | 7.8 ms | 14.1 ms | 72.2 ms | 105.9 ms | 98.8 ms |

Load generator: 0.19 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.19 | 947 MB | 764 MB | 23 | 0.265 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 34.5% | 130.3% | 2.109GiB / 7.748GiB |
| wow-e2e-bench-mongo | 111.8% | 189.7% | 2.908GiB / 7.748GiB |

Store operations, mongo (seconds -0.536 to 60.664): {epoch=61.200000047683716, insert=127115, query=327, update=158388, delete=0, getmore=0, command=959}
Store operations, kafkaEndOffsets (seconds -29.986 to 62.144): {wow.transfer.account.state=187289}

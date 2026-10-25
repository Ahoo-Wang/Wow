# run: E3

transfer, answered once the saga's entry is stored on the target; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 14, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers-tmpfs/3-C-mongo/up.

Open loop at 700.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 42000 | 0 | 2.1 ms | 5.8 ms | 9.9 ms | 16.3 ms | 24.5 ms | 17.7 ms |

Load generator: 0.18 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.30 | 1010 MB | 777 MB | 25 | 0.250 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 35.8% | 158.1% | 1.988GiB / 7.748GiB |
| wow-e2e-bench-mongo | 126.2% | 194.7% | 2.908GiB / 7.748GiB |

Store operations, mongo (seconds -0.644 to 60.554): {epoch=61.19800019264221, insert=127339, query=332, update=158612, delete=0, getmore=0, command=958}
Store operations, kafkaEndOffsets (seconds -30.032 to 62.146): {wow.transfer.account.state=187343}

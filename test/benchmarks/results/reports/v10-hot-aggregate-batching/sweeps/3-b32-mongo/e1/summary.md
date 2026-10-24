# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 27, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/batch/3-b32-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 2.3 ms | 7.6 ms | 27.1 ms | 153.6 ms | 207.9 ms | 181.2 ms |

Load generator: 0.33 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.08 | 1093 MB | 803 MB | 25 | 0.164 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.6% | 173.1% | 1.944GiB / 7.748GiB |
| wow-e2e-bench-mongo | 195.0% | 293.8% | 1.568GiB / 7.748GiB |

Store operations, mongo (seconds -0.438 to 60.883): {epoch=61.32099986076355, insert=103939, query=12571, update=134104, delete=0, getmore=47, command=9349}
Store operations, kafkaEndOffsets (seconds -29.739 to 62.098): {wow.transfer.account.state=184843}

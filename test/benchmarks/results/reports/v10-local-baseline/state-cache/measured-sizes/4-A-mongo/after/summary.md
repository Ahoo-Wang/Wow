# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 33, settleSeconds 12, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/4-A-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 4.8 ms | 23.9 ms | 55.3 ms | 156.7 ms | 210.5 ms | 193.5 ms |

Load generator: 0.40 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.41 | 914 MB | 800 MB | 25 | 0.223 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 62.5% | 177.8% | 1.908GiB / 7.748GiB |
| wow-e2e-bench-mongo | 312.7% | 486.0% | 2.056GiB / 7.748GiB |

Store operations, mongo (seconds -0.893 to 60.621): {epoch=61.513999938964844, insert=104726, query=24060, update=134372, delete=0, getmore=0, command=24541}
Store operations, kafkaEndOffsets (seconds -29.981 to 62.131): {wow.transfer.account.state=151957}

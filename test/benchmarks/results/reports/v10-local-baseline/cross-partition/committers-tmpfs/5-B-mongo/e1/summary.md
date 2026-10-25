# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 15, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers-tmpfs/5-B-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 1.2 ms | 3.3 ms | 5.5 ms | 9.7 ms | 20.0 ms | 9.5 ms |

Load generator: 0.28 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.97 | 1101 MB | 756 MB | 22 | 0.124 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 40.5% | 163.4% | 1.716GiB / 7.748GiB |
| wow-e2e-bench-mongo | 105.3% | 184.5% | 2.079GiB / 7.748GiB |

Store operations, mongo (seconds -0.292 to 60.923): {epoch=61.21499991416931, insert=103695, query=324, update=134533, delete=0, getmore=0, command=777}
Store operations, kafkaEndOffsets (seconds -29.844 to 61.946): {wow.transfer.account.state=152929}

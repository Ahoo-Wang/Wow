# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 18, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/mergers/2-B-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1712.5/s | 102752 | 448 | 4.7 ms | 464.9 ms | 1900.5 ms | 2506.8 ms | 2926.6 ms | 2801.7 ms |

Errors: {exception-SocketException=448}

Load generator: 0.41 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.05 | 949 MB | 741 MB | 18 | 0.155 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 66.2% | 185.2% | 1.648GiB / 7.748GiB |
| wow-e2e-bench-mongo | 192.5% | 834.7% | 1.416GiB / 7.748GiB |

Store operations, mongo (seconds -0.166 to 60.916): {epoch=61.08200001716614, insert=103007, query=328, update=132650, delete=0, getmore=0, command=863}
Store operations, kafkaEndOffsets (seconds -29.808 to 62.351): {wow.transfer.account.state=151917}

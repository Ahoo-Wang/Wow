# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 18, settleSeconds 21, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/3-B-mongo/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 3.0 ms | 19.8 ms | 60.4 ms | 163.8 ms | 209.3 ms | 187.4 ms |

Load generator: 0.37 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.20 | 1035 MB | 821 MB | 32 | 0.201 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.5% | 182.0% | 2.058GiB / 7.748GiB |
| wow-e2e-bench-mongo | 242.7% | 435.6% | 1.884GiB / 7.748GiB |

Store operations, mongo (seconds -0.771 to 60.725): {epoch=61.49600005149841, insert=104499, query=326, update=134657, delete=0, getmore=0, command=824}
Store operations, kafkaEndOffsets (seconds -29.788 to 62.101): {wow.transfer.account.state=151930}

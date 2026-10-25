# run: E1

deposit, answered once stored; backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka mongo, primed true, primeSeconds 13, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/item20/run/up.

Open loop at 1720.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1720.0/s | 103200 | 0 | 84.5 ms | 589.8 ms | 962.6 ms | 1474.6 ms | 1765.0 ms | 1556.5 ms |

Load generator: 0.72 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.12 | 827 MB | 864 MB | 23 | 1.244 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 124.5% | 301.9% | 985.9MiB / 7.748GiB |
| wow-e2e-bench-mongo | 605.5% | 904.4% | 4.786GiB / 7.748GiB |

Store operations, mongo (seconds -0.735 to 60.076): {epoch=60.81099987030029, insert=105203, query=20328, update=130830, delete=0, getmore=126, command=19139}
Store operations, kafkaEndOffsets (seconds -29.681 to 62.457): {wow.transfer.account.state=148139}

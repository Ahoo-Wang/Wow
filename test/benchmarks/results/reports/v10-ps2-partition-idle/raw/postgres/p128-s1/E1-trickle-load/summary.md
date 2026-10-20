# counts-E1-trickle: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Corretto-21.0.12.9.1 (build 21.0.12.1+9-LTS) OpenJDK 64-Bit Server VM Corretto-21.0.12.9.1 (build 21.0.12.1+9-LTS, mixed mode, sharing), stores kafka postgres, serverArgs --wow.log.partitions=128 --bench.extra-subscriptions=1.

Open loop at 10.0/s, uniform over 1000 accounts, 0 s warm-up, 30 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10.0/s | 300 | 0 | 15.5 ms | 22.3 ms | 24.1 ms | 54.3 ms | 54.3 ms | 54.3 ms |

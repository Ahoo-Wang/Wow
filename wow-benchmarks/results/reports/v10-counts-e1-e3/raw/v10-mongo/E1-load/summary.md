# counts-E1: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend mongo, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Corretto-21.0.12.9.1 (build 21.0.12.1+9-LTS) OpenJDK 64-Bit Server VM Corretto-21.0.12.9.1 (build 21.0.12.1+9-LTS, mixed mode, sharing), stores kafka mongo, serverArgs .

Open loop at 20.0/s, uniform over 1000 accounts, 0 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 20.0/s | 1200 | 0 | 13.9 ms | 46.1 ms | 81.9 ms | 169.0 ms | 182.7 ms | 182.7 ms |

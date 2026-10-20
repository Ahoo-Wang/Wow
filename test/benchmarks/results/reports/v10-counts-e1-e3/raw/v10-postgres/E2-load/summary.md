# counts-E2: v10 E2

deposit answered at the snapshot, then a state read that shows it; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Corretto-21.0.12.9.1 (build 21.0.12.1+9-LTS) OpenJDK 64-Bit Server VM Corretto-21.0.12.9.1 (build 21.0.12.1+9-LTS, mixed mode, sharing), stores kafka postgres, serverArgs .

Open loop at 20.0/s, uniform over 1000 accounts, 0 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 20.0/s | 1200 | 0 | 16.6 ms | 27.1 ms | 33.3 ms | 49.9 ms | 60.0 ms | 60.0 ms |

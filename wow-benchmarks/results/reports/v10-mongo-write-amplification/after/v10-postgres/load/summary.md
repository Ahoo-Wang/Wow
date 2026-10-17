# after-v10-postgres: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); .

Open loop at 20.0/s, uniform over 1000 accounts, 0 s warm-up, 30 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 20.0/s | 600 | 0 | 13.5 ms | 18.6 ms | 20.6 ms | 56.1 ms | 56.1 ms | 56.1 ms |

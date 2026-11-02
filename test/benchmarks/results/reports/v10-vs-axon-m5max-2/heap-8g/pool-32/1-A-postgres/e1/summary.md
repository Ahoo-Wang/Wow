# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 15, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/heap-8g/pool-32/1-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.6 ms | 2.7 ms | 4.3 ms | 16.2 ms | 28.0 ms | 21.6 ms |

Load generator: 0.37 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.01 | 5718 MB | 4823 MB | 2 | 0.021 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 33.5% | 120.2% | 363MiB / 31.36GiB |
| wow-e2e-bench-postgres | 76.7% | 90.3% | 277.4MiB / 31.36GiB |

Store operations, postgres (seconds -0.057 to 40.943): {epoch=40.99941301345825, xact_commit=195839, xact_rollback=0, tup_inserted=100379, tup_updated=770, tup_deleted=6, tup_fetched=588032, tup_returned=852689, blks_read=1, blks_hit=3890422, wal_records=513321, wal_fpi=1, wal_bytes=64953156, wal_buffers_full=0, wal_write=74981, wal_sync=74981, wal_write_time=329.97999999999996, wal_sync_time=36477.82000000001}

PostgreSQL per second: commits 4777, WAL flushes 1829, WAL flush time 890 ms (max 1118 ms), WAL write time 8 ms

# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 23, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/sessions/3-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.8 ms | 4.0 ms | 19.7 ms | 31.0 ms | 46.1 ms | 26.4 ms |

Load generator: 0.40 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.29 | 1459 MB | 817 MB | 22 | 0.531 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 34.9% | 139.9% | 363.8MiB / 31.36GiB |
| wow-e2e-bench-postgres | 82.5% | 103.7% | 230MiB / 31.36GiB |

Store operations, postgres (seconds -0.064 to 40.936): {epoch=40.99992895126343, xact_commit=189177, xact_rollback=0, tup_inserted=102128, tup_updated=910, tup_deleted=6, tup_fetched=631171, tup_returned=884892, blks_read=0, blks_hit=3933900, wal_records=523340, wal_fpi=2, wal_bytes=66617745, wal_buffers_full=0, wal_write=73945, wal_sync=73945, wal_write_time=355.5559999999999, wal_sync_time=37688.15700000001}

PostgreSQL per second: commits 4614, WAL flushes 1804, WAL flush time 919 ms (max 965 ms), WAL write time 9 ms

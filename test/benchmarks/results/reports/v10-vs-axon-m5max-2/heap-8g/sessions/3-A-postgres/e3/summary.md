# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 23, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/heap-8g/sessions/3-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.7 ms | 5.9 ms | 9.1 ms | 22.7 ms | 35.3 ms | 29.4 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.71 | 5690 MB | 4793 MB | 2 | 0.021 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 31.2% | 108.2% | 364.3MiB / 31.36GiB |
| wow-e2e-bench-postgres | 70.7% | 85.6% | 318MiB / 31.36GiB |

Store operations, postgres (seconds -0.052 to 40.947): {epoch=40.99902892112732, xact_commit=188241, xact_rollback=0, tup_inserted=112241, tup_updated=57559, tup_deleted=6, tup_fetched=681986, tup_returned=1220106, blks_read=0, blks_hit=4102566, wal_records=605520, wal_fpi=1, wal_bytes=89519048, wal_buffers_full=0, wal_write=67962, wal_sync=67962, wal_write_time=316.2099999999999, wal_sync_time=32346.305999999982}

PostgreSQL per second: commits 4590, WAL flushes 1657, WAL flush time 789 ms (max 857 ms), WAL write time 8 ms

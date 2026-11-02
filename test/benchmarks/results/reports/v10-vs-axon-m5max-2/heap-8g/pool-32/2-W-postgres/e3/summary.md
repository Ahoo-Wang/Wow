# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/heap-8g/pool-32/2-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.5 ms | 5.8 ms | 9.0 ms | 17.4 ms | 31.3 ms | 24.2 ms |

Load generator: 0.13 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.93 | 5643 MB | 4901 MB | 1 | 0.006 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 29.0% | 106.1% | 369.7MiB / 31.36GiB |
| wow-e2e-bench-postgres | 54.8% | 91.3% | 768.8MiB / 31.36GiB |

Store operations, postgres (seconds -0.058 to 40.943): {epoch=41.00109386444092, xact_commit=155087, xact_rollback=0, tup_inserted=84861, tup_updated=188855, tup_deleted=6, tup_fetched=252760, tup_returned=295267, blks_read=0, blks_hit=2291162, wal_records=880695, wal_fpi=0, wal_bytes=125220873, wal_buffers_full=0, wal_write=78968, wal_sync=78968, wal_write_time=345.89599999999996, wal_sync_time=37191.27399999999}

PostgreSQL per second: commits 3783, WAL flushes 1926, WAL flush time 907 ms (max 950 ms), WAL write time 8 ms

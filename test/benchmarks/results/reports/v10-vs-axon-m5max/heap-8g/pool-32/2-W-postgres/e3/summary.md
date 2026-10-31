# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/pool-32/2-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.1 ms | 4.1 ms | 6.9 ms | 23.4 ms | 35.9 ms | 30.1 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.98 | 6264 MB | 5124 MB | 1 | 0.013 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 26.1% | 103.7% | 388MiB / 31.36GiB |
| wow-e2e-bench-postgres | 50.1% | 78.9% | 764.6MiB / 31.36GiB |

Store operations, postgres (seconds -0.061 to 40.94): {epoch=41.00163412094116, xact_commit=162758, xact_rollback=0, tup_inserted=85304, tup_updated=193352, tup_deleted=7, tup_fetched=248886, tup_returned=292227, blks_read=0, blks_hit=2300070, wal_records=887483, wal_fpi=0, wal_bytes=126095505, wal_buffers_full=0, wal_write=84759, wal_sync=84759, wal_write_time=308.054, wal_sync_time=37625.931}

PostgreSQL per second: commits 3970, WAL flushes 2067, WAL flush time 918 ms (max 950 ms), WAL write time 8 ms

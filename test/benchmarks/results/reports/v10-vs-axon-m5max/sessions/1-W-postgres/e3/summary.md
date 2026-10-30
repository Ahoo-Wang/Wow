# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/sessions/1-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.2 ms | 4.5 ms | 6.8 ms | 17.0 ms | 25.8 ms | 17.2 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.04 | 1492 MB | 971 MB | 42 | 0.060 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 26.1% | 101.8% | 360.8MiB / 31.36GiB |
| wow-e2e-bench-postgres | 50.8% | 75.5% | 738.2MiB / 31.36GiB |

Store operations, postgres (seconds -0.055 to 40.945): {epoch=41.000434160232544, xact_commit=159765, xact_rollback=0, tup_inserted=84521, tup_updated=191090, tup_deleted=7, tup_fetched=245805, tup_returned=289906, blks_read=0, blks_hit=2273191, wal_records=855630, wal_fpi=0, wal_bytes=122946960, wal_buffers_full=0, wal_write=82018, wal_sync=82018, wal_write_time=302.4150000000001, wal_sync_time=37469.64199999999}

PostgreSQL per second: commits 3897, WAL flushes 2001, WAL flush time 914 ms (max 991 ms), WAL write time 7 ms

# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 23, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/heap-8g/sessions/3-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.4 ms | 5.2 ms | 8.1 ms | 21.8 ms | 34.3 ms | 26.5 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.72 | 5737 MB | 4920 MB | 2 | 0.021 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 28.3% | 114.8% | 505MiB / 31.36GiB |
| wow-e2e-bench-postgres | 77.8% | 93.1% | 317.6MiB / 31.36GiB |

Store operations, postgres (seconds -0.054 to 40.947): {epoch=41.00088620185852, xact_commit=197984, xact_rollback=0, tup_inserted=112258, tup_updated=57984, tup_deleted=6, tup_fetched=708475, tup_returned=1280152, blks_read=0, blks_hit=4400953, wal_records=606272, wal_fpi=0, wal_bytes=89507910, wal_buffers_full=0, wal_write=67259, wal_sync=67259, wal_write_time=317.61199999999997, wal_sync_time=32317.139999999985}

PostgreSQL per second: commits 4829, WAL flushes 1640, WAL flush time 788 ms (max 866 ms), WAL write time 8 ms

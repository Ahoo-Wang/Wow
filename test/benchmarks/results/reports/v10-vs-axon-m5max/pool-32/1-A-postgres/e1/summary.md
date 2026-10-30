# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 15, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/pool-32/1-A-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.7 ms | 2.9 ms | 16.3 ms | 28.7 ms | 35.1 ms | 27.0 ms |

Load generator: 0.36 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.24 | 1454 MB | 795 MB | 23 | 0.556 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 31.0% | 133.6% | 363.7MiB / 31.36GiB |
| wow-e2e-bench-postgres | 89.1% | 106.7% | 277.2MiB / 31.36GiB |

Store operations, postgres (seconds -0.041 to 40.959): {epoch=41.00027108192444, xact_commit=197317, xact_rollback=0, tup_inserted=100143, tup_updated=900, tup_deleted=6, tup_fetched=663783, tup_returned=941066, blks_read=0, blks_hit=4186783, wal_records=512831, wal_fpi=1, wal_bytes=65115011, wal_buffers_full=0, wal_write=72261, wal_sync=72261, wal_write_time=369.32499999999993, wal_sync_time=36933.27799999999}

PostgreSQL per second: commits 4812, WAL flushes 1763, WAL flush time 901 ms (max 962 ms), WAL write time 9 ms

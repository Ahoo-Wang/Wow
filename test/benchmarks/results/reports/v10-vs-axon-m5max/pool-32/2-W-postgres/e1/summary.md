# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/pool-32/2-W-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.5 ms | 5.6 ms | 15.7 ms | 102.4 ms | 122.8 ms | 113.2 ms |

Load generator: 0.36 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.17 | 1490 MB | 967 MB | 41 | 0.070 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 26.2% | 104.5% | 362MiB / 31.36GiB |
| wow-e2e-bench-postgres | 55.4% | 75.0% | 692.6MiB / 31.36GiB |

Store operations, postgres (seconds -0.057 to 40.947): {epoch=41.00389099121094, xact_commit=159262, xact_rollback=0, tup_inserted=101886, tup_updated=224056, tup_deleted=6, tup_fetched=327908, tup_returned=391160, blks_read=0, blks_hit=2629550, wal_records=1058741, wal_fpi=0, wal_bytes=125123628, wal_buffers_full=0, wal_write=73340, wal_sync=73340, wal_write_time=283.165, wal_sync_time=39009.471000000005}

PostgreSQL per second: commits 3884, WAL flushes 1788, WAL flush time 951 ms (max 1011 ms), WAL write time 7 ms

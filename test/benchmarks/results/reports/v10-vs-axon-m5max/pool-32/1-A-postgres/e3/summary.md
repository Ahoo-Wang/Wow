# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 15, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/pool-32/1-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.6 ms | 5.6 ms | 11.6 ms | 28.8 ms | 35.3 ms | 28.8 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.84 | 1596 MB | 967 MB | 21 | 0.293 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 27.5% | 109.5% | 365.5MiB / 31.36GiB |
| wow-e2e-bench-postgres | 80.2% | 95.2% | 364.7MiB / 31.36GiB |

Store operations, postgres (seconds -0.046 to 40.956): {epoch=41.001505851745605, xact_commit=199120, xact_rollback=0, tup_inserted=112401, tup_updated=60518, tup_deleted=6, tup_fetched=776591, tup_returned=1134353, blks_read=0, blks_hit=4432636, wal_records=612155, wal_fpi=0, wal_bytes=89976216, wal_buffers_full=0, wal_write=66788, wal_sync=66788, wal_write_time=345.804, wal_sync_time=32301.888999999996}

PostgreSQL per second: commits 4857, WAL flushes 1629, WAL flush time 788 ms (max 877 ms), WAL write time 8 ms

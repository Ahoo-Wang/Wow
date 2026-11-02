# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 8g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 24, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/heap-8g/sessions/2-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.7 ms | 5.9 ms | 9.1 ms | 26.2 ms | 34.0 ms | 28.5 ms |

Load generator: 0.13 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.72 | 5698 MB | 4887 MB | 2 | 0.021 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 30.6% | 128.1% | 365.3MiB / 31.36GiB |
| wow-e2e-bench-postgres | 71.4% | 83.7% | 318.4MiB / 31.36GiB |

Store operations, postgres (seconds -0.049 to 40.955): {epoch=41.00399398803711, xact_commit=187982, xact_rollback=0, tup_inserted=112307, tup_updated=57006, tup_deleted=6, tup_fetched=684371, tup_returned=1214328, blks_read=0, blks_hit=4105400, wal_records=604616, wal_fpi=1, wal_bytes=89426646, wal_buffers_full=0, wal_write=68375, wal_sync=68375, wal_write_time=309.58500000000004, wal_sync_time=32601.858000000007}

PostgreSQL per second: commits 4585, WAL flushes 1668, WAL flush time 795 ms (max 888 ms), WAL write time 8 ms

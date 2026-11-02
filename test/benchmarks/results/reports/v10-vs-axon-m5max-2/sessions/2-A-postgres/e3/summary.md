# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 28, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/sessions/2-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.7 ms | 6.5 ms | 18.4 ms | 32.3 ms | 42.3 ms | 31.4 ms |

Load generator: 0.13 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.88 | 1495 MB | 824 MB | 18 | 0.380 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 31.7% | 129.7% | 366.5MiB / 31.36GiB |
| wow-e2e-bench-postgres | 71.7% | 86.4% | 318.9MiB / 31.36GiB |

Store operations, postgres (seconds -0.062 to 40.942): {epoch=41.00409507751465, xact_commit=186713, xact_rollback=0, tup_inserted=112277, tup_updated=57330, tup_deleted=6, tup_fetched=739896, tup_returned=1150713, blks_read=1, blks_hit=4184882, wal_records=605110, wal_fpi=2, wal_bytes=90132147, wal_buffers_full=0, wal_write=67816, wal_sync=67816, wal_write_time=302.1639999999999, wal_sync_time=32398.34000000001}

PostgreSQL per second: commits 4554, WAL flushes 1654, WAL flush time 790 ms (max 876 ms), WAL write time 7 ms

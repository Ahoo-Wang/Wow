# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/pool-32/2-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.3 ms | 4.4 ms | 6.8 ms | 14.8 ms | 32.2 ms | 19.3 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.91 | 1284 MB | 759 MB | 9 | 0.024 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 27.6% | 99.4% | 366.7MiB / 31.36GiB |
| wow-e2e-bench-postgres | 50.3% | 79.0% | 767.3MiB / 31.36GiB |

Store operations, postgres (seconds -0.056 to 40.944): {epoch=41.000622034072876, xact_commit=159629, xact_rollback=0, tup_inserted=84824, tup_updated=189013, tup_deleted=7, tup_fetched=246794, tup_returned=289243, blks_read=0, blks_hit=2275664, wal_records=863684, wal_fpi=0, wal_bytes=124207504, wal_buffers_full=0, wal_write=82199, wal_sync=82199, wal_write_time=292.72400000000005, wal_sync_time=37147.41900000001}

PostgreSQL per second: commits 3894, WAL flushes 2005, WAL flush time 906 ms (max 943 ms), WAL write time 7 ms

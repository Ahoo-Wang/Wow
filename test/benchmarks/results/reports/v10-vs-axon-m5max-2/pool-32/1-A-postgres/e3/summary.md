# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/pool-32/1-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.7 ms | 6.6 ms | 17.5 ms | 32.0 ms | 37.9 ms | 29.3 ms |

Load generator: 0.13 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.88 | 1490 MB | 851 MB | 19 | 0.398 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 30.7% | 96.3% | 368.9MiB / 31.36GiB |
| wow-e2e-bench-postgres | 75.0% | 94.8% | 365.3MiB / 31.36GiB |

Store operations, postgres (seconds -0.036 to 40.963): {epoch=40.998393058776855, xact_commit=188610, xact_rollback=0, tup_inserted=112136, tup_updated=58088, tup_deleted=6, tup_fetched=747477, tup_returned=1070074, blks_read=0, blks_hit=4214900, wal_records=606335, wal_fpi=0, wal_bytes=89745697, wal_buffers_full=0, wal_write=68094, wal_sync=68094, wal_write_time=307.7560000000001, wal_sync_time=32252.329000000012}

PostgreSQL per second: commits 4601, WAL flushes 1661, WAL flush time 787 ms (max 871 ms), WAL write time 8 ms

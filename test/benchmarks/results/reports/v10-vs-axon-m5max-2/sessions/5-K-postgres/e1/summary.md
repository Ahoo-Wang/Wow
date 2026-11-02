# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_snapshot_3/test/benchmarks/results/reports/v10-vs-axon-m5max-2/sessions/5-K-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.5 ms | 2.7 ms | 5.5 ms | 15.8 ms | 27.8 ms | 20.9 ms |

Load generator: 0.40 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.40 | 1324 MB | 762 MB | 16 | 0.040 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 34.0% | 112.0% | 993.7MiB / 31.36GiB |
| wow-e2e-bench-postgres | 60.1% | 80.7% | 620.5MiB / 31.36GiB |

Store operations, postgres (seconds -0.043 to 40.954): {epoch=40.99697709083557, xact_commit=161955, xact_rollback=0, tup_inserted=101078, tup_updated=223342, tup_deleted=6, tup_fetched=331184, tup_returned=391334, blks_read=0, blks_hit=2626378, wal_records=1038604, wal_fpi=2, wal_bytes=123966234, wal_buffers_full=0, wal_write=75354, wal_sync=75354, wal_write_time=332.57800000000003, wal_sync_time=37421.10899999999}
Store operations, kafkaEndOffsets (seconds -19.892 to 41.782): {wow.transfer.account.state=147729, wow.transfer.commands=0}

PostgreSQL per second: commits 3950, WAL flushes 1838, WAL flush time 913 ms (max 961 ms), WAL write time 8 ms

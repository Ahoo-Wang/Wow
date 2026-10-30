# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-060937-run-postgres-n1-saturation.

Open loop at 16590.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 16523.4/s | 991405 | 3995 | 14.1 ms | 39.7 ms | 72.2 ms | 199.7 ms | 245.7 ms | 215.0 ms |

Errors: {generator-overload=3995}

Load generator: 2.61 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.99 | 1483 MB | 859 MB | 150 | 0.483 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.9% | 155.0% | 993.3MiB / 31.36GiB |
| wow-e2e-bench-postgres | 158.7% | 179.6% | 861.7MiB / 31.36GiB |

Store operations, postgres (seconds -0.07 to 60.93): {epoch=61.00044298171997, xact_commit=113935, xact_rollback=0, tup_inserted=1001252, tup_updated=1482577, tup_deleted=7, tup_fetched=2875770, tup_returned=3346378, blks_read=2132, blks_hit=22981919, wal_records=7592676, wal_fpi=3, wal_bytes=1131834751, wal_buffers_full=0, wal_write=53791, wal_sync=53791, wal_write_time=543.545, wal_sync_time=52318.601}
Store operations, kafkaEndOffsets (seconds -29.855 to 61.992): {wow.transfer.account.state=1472135, wow.transfer.commands=0}

PostgreSQL per second: commits 1868, WAL flushes 882, WAL flush time 858 ms (max 997 ms), WAL write time 9 ms

# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-060700-run-postgres-n1-saturation.

Open loop at 16590.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 16336.5/s | 980192 | 15208 | 15.9 ms | 49.2 ms | 176.1 ms | 219.1 ms | 262.6 ms | 226.3 ms |

Errors: {generator-overload=15208}

Load generator: 2.56 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.83 | 1474 MB | 833 MB | 148 | 0.469 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.0% | 153.6% | 992.1MiB / 31.36GiB |
| wow-e2e-bench-postgres | 157.1% | 189.8% | 840.8MiB / 31.36GiB |

Store operations, postgres (seconds -0.062 to 60.942): {epoch=61.003814935684204, xact_commit=102837, xact_rollback=0, tup_inserted=985176, tup_updated=1417022, tup_deleted=7, tup_fetched=2745372, tup_returned=3728121, blks_read=2123, blks_hit=23015967, wal_records=7363545, wal_fpi=3, wal_bytes=1109158435, wal_buffers_full=0, wal_write=49056, wal_sync=49056, wal_write_time=524.53, wal_sync_time=52230.56}
Store operations, kafkaEndOffsets (seconds -29.831 to 62.015): {wow.transfer.account.state=1461319, wow.transfer.commands=0}

PostgreSQL per second: commits 1686, WAL flushes 804, WAL flush time 856 ms (max 1143 ms), WAL write time 9 ms

# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-045921-run-postgres-n1-saturation.

Open loop at 16590.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 16340.5/s | 980428 | 14972 | 23.3 ms | 63.7 ms | 157.7 ms | 497.7 ms | 682.8 ms | 647.2 ms |

Errors: {generator-overload=14972}

Load generator: 2.54 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.80 | 1395 MB | 842 MB | 165 | 0.472 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 44.7% | 144.0% | 1.255GiB / 31.36GiB |
| wow-e2e-bench-postgres | 177.0% | 192.3% | 725.5MiB / 31.36GiB |

Store operations, postgres (seconds -0.063 to 60.937): {epoch=61.00020790100098, xact_commit=95007, xact_rollback=0, tup_inserted=978224, tup_updated=1308921, tup_deleted=0, tup_fetched=2757747, tup_returned=2764242, blks_read=513273, blks_hit=26248938, wal_records=6773418, wal_fpi=151, wal_bytes=1092680793, wal_buffers_full=0, wal_write=49108, wal_sync=49108, wal_write_time=752.7270000000008, wal_sync_time=49370.1810000001}
Store operations, kafkaEndOffsets (seconds -30.03 to 61.995): {wow.transfer.account.state=1453152, wow.transfer.commands=0}

PostgreSQL per second: commits 1557, WAL flushes 805, WAL flush time 809 ms (max 986 ms), WAL write time 12 ms

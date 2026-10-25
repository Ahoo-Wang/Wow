# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-101633-run-postgres-n1-saturation.

Open loop at 8949.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8398.8/s | 503927 | 33013 | 7.5 ms | 153.6 ms | 679.9 ms | 3801.1 ms | 4013.6 ms | 3883.0 ms |

Errors: {http-429-wow.command.busy=33013}

Load generator: 2.15 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.49 | 1149 MB | 826 MB | 77 | 0.350 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.9% | 145.9% | 2.064GiB / 15.6GiB |
| wow-e2e-bench-postgres | 116.5% | 170.8% | 996.1MiB / 15.6GiB |

Store operations, postgres (seconds -0.988 to 60.013): {epoch=61.00080919265747, xact_commit=180536, xact_rollback=0, tup_inserted=516301, tup_updated=948133, tup_deleted=9, tup_fetched=1764736, tup_returned=3780080, blks_read=22639, blks_hit=14725335, wal_records=4377210, wal_fpi=2, wal_bytes=602097330, wal_buffers_full=0, wal_write=114463, wal_sync=114463, wal_write_time=793.7089999999998, wal_sync_time=34557.986999999994}
Store operations, kafkaEndOffsets (seconds -29.644 to 62.237): {wow.transfer.account.state=757968}

PostgreSQL per second: commits 2959, WAL flushes 1876, WAL flush time 566 ms (max 959 ms), WAL write time 13 ms

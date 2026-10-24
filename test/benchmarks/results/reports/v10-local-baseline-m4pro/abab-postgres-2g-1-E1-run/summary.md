# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-postgres-2g-1-up.

Open loop at 6092.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5921.8/s | 355309 | 10210 | 31.7 ms | 270.3 ms | 1572.9 ms | 2023.4 ms | 2173.9 ms | 2097.2 ms |

Errors: {http-429-wow.command.busy=10210}

Load generator: 1.49 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.15 | 1916 MB | 1721 MB | 28 | 0.509 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.2% | 158.7% | 2.202GiB / 15.6GiB |
| wow-e2e-bench-postgres | 223.2% | 406.8% | 1.095GiB / 15.6GiB |

Store operations, postgres (seconds 0.0 to 60.0): {epoch=59.99983096122742, xact_commit=197146, xact_rollback=0, tup_inserted=352965, tup_updated=560738, tup_deleted=0, tup_fetched=1715052, tup_returned=2216887, blks_read=957178, blks_hit=8856865, wal_records=2672063, wal_fpi=70139, wal_bytes=813664377, wal_buffers_full=0, wal_write=71414, wal_sync=71414, wal_write_time=1950.005000000001, wal_sync_time=47655.50400000007}
Store operations, kafkaEndOffsets (seconds -29.719 to 62.245): {wow.transfer.account.state=506455}

PostgreSQL per second: commits 3287, WAL flushes 1191, WAL flush time 795 ms (max 1863 ms), WAL write time 33 ms

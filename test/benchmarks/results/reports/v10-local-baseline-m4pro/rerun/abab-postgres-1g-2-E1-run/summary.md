# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-postgres-1g-2-up.

Open loop at 6092.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5945.2/s | 356713 | 8806 | 292.9 ms | 860.2 ms | 1122.3 ms | 1499.1 ms | 2523.8 ms | 1663.0 ms |

Errors: {http-429-wow.command.busy=8806}

Load generator: 1.43 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.18 | 1149 MB | 893 MB | 117 | 1.288 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 56.1% | 162.8% | 2.334GiB / 15.6GiB |
| wow-e2e-bench-postgres | 345.7% | 449.4% | 826.5MiB / 15.6GiB |

Store operations, postgres (seconds -0.003 to 59.997): {epoch=59.99992394447327, xact_commit=264422, xact_rollback=0, tup_inserted=357431, tup_updated=403615, tup_deleted=12, tup_fetched=11006223, tup_returned=11051443, blks_read=8918100, blks_hit=11304811, wal_records=2457229, wal_fpi=39469, wal_bytes=626463452, wal_buffers_full=0, wal_write=25432, wal_sync=25429, wal_write_time=883.5589999999975, wal_sync_time=23255.920000000042}
Store operations, kafkaEndOffsets (seconds -29.698 to 62.452): {wow.transfer.account.state=516802}

PostgreSQL per second: commits 4407, WAL flushes 424, WAL flush time 388 ms (max 686 ms), WAL write time 15 ms

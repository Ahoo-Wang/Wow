# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 3, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/abab-postgres-1g-2-up.

Open loop at 1428.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1428.0/s | 85680 | 0 | 13.1 ms | 203.8 ms | 444.4 ms | 671.7 ms | 801.1 ms | 720.9 ms |

Load generator: 0.47 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.14 | 982 MB | 819 MB | 50 | 0.356 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 48.9% | 163.3% | 2.22GiB / 15.6GiB |
| wow-e2e-bench-postgres | 235.0% | 355.0% | 1.087GiB / 15.6GiB |

Store operations, postgres (seconds -0.058 to 60.941): {epoch=60.99854588508606, xact_commit=380093, xact_rollback=0, tup_inserted=256844, tup_updated=503186, tup_deleted=0, tup_fetched=6469722, tup_returned=6477302, blks_read=5048862, blks_hit=8182011, wal_records=2213428, wal_fpi=58901, wal_bytes=709846016, wal_buffers_full=0, wal_write=123741, wal_sync=123741, wal_write_time=1597.7439999999988, wal_sync_time=47284.785999999964}
Store operations, kafkaEndOffsets (seconds -30.006 to 62.17): {wow.transfer.account.state=382994}

PostgreSQL per second: commits 6231, WAL flushes 2029, WAL flush time 775 ms (max 899 ms), WAL write time 26 ms

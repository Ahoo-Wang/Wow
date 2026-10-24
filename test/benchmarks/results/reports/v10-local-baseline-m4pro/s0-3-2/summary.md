# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 16, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/s0-up-3.

Open loop at 6346.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6346.0/s | 380760 | 0 | 2.9 ms | 10.8 ms | 41.2 ms | 218.1 ms | 294.4 ms | 255.0 ms |

Load generator: 1.72 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.27 | 1229 MB | 843 MB | 56 | 0.224 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 39.6% | 131.4% | 1.998GiB / 15.6GiB |
| wow-e2e-bench-postgres | 140.1% | 177.8% | 1.037GiB / 15.6GiB |

Store operations, postgres (seconds -0.057 to 60.944): {epoch=61.000712156295776, xact_commit=504806, xact_rollback=0, tup_inserted=382080, tup_updated=778753, tup_deleted=7, tup_fetched=1522810, tup_returned=1629757, blks_read=71241, blks_hit=9877573, wal_records=3388735, wal_fpi=12334, wal_bytes=516692549, wal_buffers_full=0, wal_write=166702, wal_sync=166702, wal_write_time=1280.984, wal_sync_time=49406.003999999986}
Store operations, kafkaEndOffsets (seconds -30.028 to 61.987): {wow.transfer.account.state=566835}

PostgreSQL per second: commits 8275, WAL flushes 2733, WAL flush time 810 ms (max 1032 ms), WAL write time 21 ms

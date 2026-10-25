# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 12, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot/test/benchmarks/results/e2e/20261024-235048-run-postgres-n1-saturation.

Open loop at 10602.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10602.0/s | 636120 | 0 | 27.5 ms | 63.5 ms | 127.0 ms | 219.1 ms | 304.5 ms | 248.8 ms |

Load generator: 2.36 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.10 | 1103 MB | 835 MB | 95 | 0.505 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.4% | 150.0% | 2.13GiB / 15.6GiB |
| wow-e2e-bench-postgres | 250.9% | 317.2% | 975.3MiB / 15.6GiB |

Store operations, postgres (seconds -0.014 to 59.986): {epoch=60.00004005432129, xact_commit=250363, xact_rollback=0, tup_inserted=625543, tup_updated=891998, tup_deleted=7, tup_fetched=1434232, tup_returned=1528645, blks_read=10516, blks_hit=13214533, wal_records=4637751, wal_fpi=3, wal_bytes=697057159, wal_buffers_full=0, wal_write=112745, wal_sync=112745, wal_write_time=1149.468, wal_sync_time=45982.887}
Store operations, kafkaEndOffsets (seconds -29.714 to 62.23): {wow.transfer.account.state=940882}

PostgreSQL per second: commits 4173, WAL flushes 1879, WAL flush time 766 ms (max 855 ms), WAL write time 19 ms

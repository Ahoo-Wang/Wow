# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 31, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s0-up-2.

Open loop at 2542.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2542.0/s | 152520 | 0 | 153.6 ms | 481.3 ms | 790.5 ms | 1089.5 ms | 1496.1 ms | 1212.4 ms |

Load generator: 0.75 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.90 | 1040 MB | 831 MB | 45 | 0.571 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 95.1% | 192.8% | 1.994GiB / 7.748GiB |
| wow-e2e-bench-postgres | 243.0% | 349.4% | 765.4MiB / 7.748GiB |

Store operations, postgres (seconds -0.048 to 60.952): {epoch=60.999507904052734, xact_commit=199188, xact_rollback=0, tup_inserted=152990, tup_updated=252583, tup_deleted=7, tup_fetched=1134783, tup_returned=1220506, blks_read=0, blks_hit=5378123, wal_records=1221486, wal_fpi=129, wal_bytes=173836578, wal_buffers_full=0, wal_write=44640, wal_sync=44640, wal_write_time=1330.1539999999995, wal_sync_time=44774.19499999998}
Store operations, kafkaEndOffsets (seconds -30.009 to 61.55): {wow.transfer.account.state=223751}

PostgreSQL per second: commits 3265, WAL flushes 732, WAL flush time 734 ms (max 869 ms), WAL write time 22 ms

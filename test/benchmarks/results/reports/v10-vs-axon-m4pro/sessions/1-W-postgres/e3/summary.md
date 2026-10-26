# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /tmp/wow-vs-axon-c48c5d5191/vs-axon/1-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.0 ms | 1.5 ms | 3.0 ms | 11.5 ms | 21.4 ms | 13.6 ms |

Load generator: 0.12 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.96 | 1101 MB | 748 MB | 8 | 0.025 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 29.4% | 106.3% | 556.5MiB / 15.6GiB |
| wow-e2e-bench-postgres | 41.5% | 76.8% | 798.2MiB / 15.6GiB |

Store operations, postgres (seconds -0.054 to 40.948): {epoch=41.001546144485474, xact_commit=169627, xact_rollback=0, tup_inserted=85073, tup_updated=188730, tup_deleted=7, tup_fetched=235222, tup_returned=280423, blks_read=0, blks_hit=2252902, wal_records=853830, wal_fpi=0, wal_bytes=125258994, wal_buffers_full=0, wal_write=167313, wal_sync=167313, wal_write_time=453.2740000000001, wal_sync_time=17215.156999999992}

PostgreSQL per second: commits 4137, WAL flushes 4081, WAL flush time 420 ms (max 465 ms), WAL write time 11 ms

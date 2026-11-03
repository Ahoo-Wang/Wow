# axon-pg-2-axon-e3: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 37, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/2-axon-up.

Open loop at 300.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 300.0/s | 12000 | 0 | 3.8 ms | 226.3 ms | 536.6 ms | 733.2 ms | 824.3 ms | 786.4 ms |

Load generator: 0.19 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.54 | 1017 MB | 665 MB | 5 | 0.126 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.2% | 164.2% | 360.5MiB / 15.61GiB |
| wow-e2e-bench-postgres | 63.9% | 85.4% | 250.2MiB / 15.61GiB |

Store operations, postgres (seconds -0.953 to 40.043): {epoch=40.99575901031494, xact_commit=78541, xact_rollback=0, tup_inserted=49281, tup_updated=24801, tup_deleted=6, tup_fetched=164738, tup_returned=374961, blks_read=0, blks_hit=1350201, wal_records=264582, wal_fpi=1, wal_bytes=38319768, wal_buffers_full=0, wal_write=32368, wal_sync=32368, wal_write_time=503.4000000000001, wal_sync_time=14656.32}

PostgreSQL per second: commits 1916, WAL flushes 790, WAL flush time 357 ms (max 797 ms), WAL write time 12 ms

# axon-pg-2-axon-e1: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 37, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/2-axon-up.

Open loop at 1000.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1000.0/s | 40000 | 0 | 1.5 ms | 172.0 ms | 344.1 ms | 651.3 ms | 724.3 ms | 696.3 ms |

Load generator: 0.30 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.60 | 1011 MB | 681 MB | 5 | 0.120 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 49.1% | 161.0% | 426.1MiB / 15.61GiB |
| wow-e2e-bench-postgres | 59.1% | 81.1% | 211.2MiB / 15.61GiB |

Store operations, postgres (seconds -0.995 to 40.004): {epoch=40.999574184417725, xact_commit=82980, xact_rollback=0, tup_inserted=41233, tup_updated=673, tup_deleted=6, tup_fetched=140292, tup_returned=181727, blks_read=0, blks_hit=1153181, wal_records=211890, wal_fpi=0, wal_bytes=26906845, wal_buffers_full=0, wal_write=36313, wal_sync=36313, wal_write_time=529.767, wal_sync_time=15363.825000000004}

PostgreSQL per second: commits 2024, WAL flushes 886, WAL flush time 375 ms (max 932 ms), WAL write time 13 ms

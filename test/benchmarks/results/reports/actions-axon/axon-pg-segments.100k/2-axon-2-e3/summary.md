# axon-pg-segments-2-axon-2-e3: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 54, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg-segments.100k/2-axon-2-up.

Open loop at 300.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 300.0/s | 12000 | 0 | 3.4 ms | 7.7 ms | 19.5 ms | 48.9 ms | 56.1 ms | 50.9 ms |

Load generator: 0.22 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.56 | 1006 MB | 682 MB | 4 | 0.151 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 58.5% | 165.8% | 364.2MiB / 15.61GiB |
| wow-e2e-bench-postgres | 82.6% | 119.2% | 221MiB / 15.61GiB |

Store operations, postgres (seconds -0.946 to 40.054): {epoch=40.999886989593506, xact_commit=85510, xact_rollback=0, tup_inserted=49266, tup_updated=24600, tup_deleted=6, tup_fetched=161581, tup_returned=241240, blks_read=0, blks_hit=1378338, wal_records=264277, wal_fpi=1, wal_bytes=37908535, wal_buffers_full=0, wal_write=36688, wal_sync=36688, wal_write_time=510.4609999999998, wal_sync_time=5125.058000000001}

PostgreSQL per second: commits 2086, WAL flushes 895, WAL flush time 125 ms (max 161 ms), WAL write time 12 ms

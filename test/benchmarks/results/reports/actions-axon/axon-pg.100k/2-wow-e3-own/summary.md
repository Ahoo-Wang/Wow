# axon-pg-2-wow-e3-own: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 44, settleSeconds 3, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/2-wow-up.

Open loop at 313.5/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 313.5/s | 12540 | 0 | 5.1 ms | 172.0 ms | 276.5 ms | 387.1 ms | 419.2 ms | 411.6 ms |

Load generator: 0.18 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.53 | 1334 MB | 754 MB | 8 | 0.132 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.5% | 166.4% | 377.9MiB / 15.61GiB |
| wow-e2e-bench-postgres | 49.6% | 61.8% | 846.3MiB / 15.61GiB |

Store operations, postgres (seconds -0.987 to 40.013): {epoch=41.0000638961792, xact_commit=54233, xact_rollback=0, tup_inserted=38251, tup_updated=95806, tup_deleted=0, tup_fetched=148395, tup_returned=153052, blks_read=59, blks_hit=1136458, wal_records=393913, wal_fpi=560, wal_bytes=60024194, wal_buffers_full=0, wal_write=49181, wal_sync=49181, wal_write_time=659.9629999999997, wal_sync_time=19740.704000000027}

PostgreSQL per second: commits 1323, WAL flushes 1199, WAL flush time 481 ms (max 787 ms), WAL write time 16 ms

# axon-pg-1-axon-e1-own: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 37, settleSeconds 11, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/1-axon-up.

Open loop at 1569.8/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1569.8/s | 62792 | 0 | 1.5 ms | 40.2 ms | 108.5 ms | 185.3 ms | 386.3 ms | 208.9 ms |

Load generator: 0.47 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.82 | 1314 MB | 714 MB | 8 | 0.166 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.7% | 139.3% | 418.9MiB / 15.61GiB |
| wow-e2e-bench-postgres | 68.5% | 99.2% | 665.4MiB / 15.61GiB |

Store operations, postgres (seconds -0.979 to 40.021): {epoch=40.99990701675415, xact_commit=107511, xact_rollback=0, tup_inserted=64613, tup_updated=686, tup_deleted=9, tup_fetched=164622, tup_returned=207618, blks_read=14782, blks_hit=1334026, wal_records=327606, wal_fpi=0, wal_bytes=42884042, wal_buffers_full=0, wal_write=58580, wal_sync=58580, wal_write_time=885.9660000000003, wal_sync_time=13448.929999999993}

PostgreSQL per second: commits 2622, WAL flushes 1429, WAL flush time 328 ms (max 598 ms), WAL write time 22 ms

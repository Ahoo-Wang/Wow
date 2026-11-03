# axon-pg-2-wow-e1-own: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 44, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg.100k/2-wow-up.

Open loop at 1680.1/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1680.1/s | 67204 | 0 | 85.5 ms | 452.6 ms | 729.1 ms | 970.8 ms | 1083.4 ms | 1024.0 ms |

Load generator: 0.45 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.64 | 1334 MB | 791 MB | 15 | 0.337 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 46.9% | 149.6% | 377.6MiB / 15.61GiB |
| wow-e2e-bench-postgres | 62.7% | 106.6% | 819.5MiB / 15.61GiB |

Store operations, postgres (seconds -0.985 to 40.014): {epoch=40.998475074768066, xact_commit=25018, xact_rollback=0, tup_inserted=67579, tup_updated=125358, tup_deleted=12, tup_fetched=193740, tup_returned=238880, blks_read=19232, blks_hit=1809131, wal_records=576050, wal_fpi=15531, wal_bytes=119434743, wal_buffers_full=0, wal_write=19379, wal_sync=19379, wal_write_time=807.5360000000001, wal_sync_time=22631.641999999993}

PostgreSQL per second: commits 610, WAL flushes 473, WAL flush time 551 ms (max 1006 ms), WAL write time 20 ms

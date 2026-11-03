# axon-pg-segments-1-axon-2-e3: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), loadJvm openjdk version "25.0.5" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS) OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 51, settleSeconds 4, accounts 100000, session /home/runner/work/_temp/results/axon-pg-segments.100k/1-axon-2-up.

Open loop at 300.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 300.0/s | 12000 | 0 | 3.4 ms | 7.8 ms | 29.1 ms | 95.7 ms | 123.0 ms | 113.2 ms |

Load generator: 0.21 cores of 2 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.56 | 1289 MB | 918 MB | 4 | 0.324 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.4% | 161.4% | 361.7MiB / 15.61GiB |
| wow-e2e-bench-postgres | 83.7% | 122.2% | 221.2MiB / 15.61GiB |

Store operations, postgres (seconds -0.003 to 39.997): {epoch=39.99949312210083, xact_commit=82449, xact_rollback=0, tup_inserted=48197, tup_updated=24121, tup_deleted=6, tup_fetched=151109, tup_returned=231543, blks_read=0, blks_hit=1329654, wal_records=258897, wal_fpi=1, wal_bytes=37376843, wal_buffers_full=0, wal_write=35940, wal_sync=35940, wal_write_time=480.4850000000001, wal_sync_time=5031.648999999998}

PostgreSQL per second: commits 2061, WAL flushes 899, WAL flush time 126 ms (max 202 ms), WAL write time 12 ms

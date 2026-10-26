# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 6, settleSeconds 4, accounts 100000, session /tmp/wow-bench-m5pro/vs-axon/1-W-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 0.9 ms | 2.5 ms | 7.6 ms | 60.2 ms | 99.1 ms | 81.4 ms |

Load generator: 0.15 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.85 | 1173 MB | 689 MB | 8 | 0.024 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 30.7% | 92.9% | 380.7MiB / 15.6GiB |
| wow-e2e-bench-postgres | 46.4% | 93.1% | 746.6MiB / 15.6GiB |

Store operations, postgres (seconds -0.059 to 40.94): {epoch=40.99871516227722, xact_commit=168396, xact_rollback=0, tup_inserted=84830, tup_updated=189198, tup_deleted=7, tup_fetched=237099, tup_returned=281886, blks_read=0, blks_hit=2256330, wal_records=854387, wal_fpi=0, wal_bytes=125415004, wal_buffers_full=0, wal_write=161858, wal_sync=161858, wal_write_time=495.2560000000001, wal_sync_time=20929.202999999994}

PostgreSQL per second: commits 4107, WAL flushes 3948, WAL flush time 510 ms (max 673 ms), WAL write time 12 ms

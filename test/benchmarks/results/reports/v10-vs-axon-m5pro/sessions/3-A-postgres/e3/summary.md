# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 30, settleSeconds 3, accounts 100000, session /tmp/wow-bench-m5pro/vs-axon/3-A-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 3.8 ms | 6.7 ms | 20.1 ms | 37.6 ms | 50.5 ms | 33.8 ms |

Load generator: 0.18 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.99 | 1417 MB | 809 MB | 19 | 0.422 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 34.8% | 146.5% | 423.1MiB / 15.6GiB |
| wow-e2e-bench-postgres | 73.1% | 90.2% | 348.7MiB / 15.6GiB |

Store operations, postgres (seconds -0.06 to 40.944): {epoch=41.00393605232239, xact_commit=159888, xact_rollback=0, tup_inserted=112270, tup_updated=59213, tup_deleted=6, tup_fetched=646546, tup_returned=806984, blks_read=0, blks_hit=3368483, wal_records=608834, wal_fpi=1, wal_bytes=90621348, wal_buffers_full=0, wal_write=79457, wal_sync=79457, wal_write_time=446.83899999999994, wal_sync_time=17382.553}

PostgreSQL per second: commits 3900, WAL flushes 1938, WAL flush time 424 ms (max 564 ms), WAL write time 11 ms

# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 18, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-postgres-up.

Open loop at 2501.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2501.0/s | 150060 | 0 | 0.7 ms | 1.5 ms | 8.9 ms | 88.1 ms | 117.3 ms | 99.8 ms |

Load generator: 0.46 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.73 | 886 MB | 795 MB | 22 | 0.053 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 34.4% | 127.1% | 2.105GiB / 15.6GiB |
| wow-e2e-bench-postgres | 70.8% | 95.7% | 772.8MiB / 15.6GiB |

Store operations, postgres (seconds -0.054 to 60.946): {epoch=61.00037217140198, xact_commit=301476, xact_rollback=0, tup_inserted=150696, tup_updated=332509, tup_deleted=9, tup_fetched=621246, tup_returned=853811, blks_read=147735, blks_hit=4021052, wal_records=1519854, wal_fpi=4675, wal_bytes=212567948, wal_buffers_full=0, wal_write=269173, wal_sync=269173, wal_write_time=1125.5769999999993, wal_sync_time=39534.513000000035}
Store operations, kafkaEndOffsets (seconds -30.027 to 61.931): {wow.transfer.account.state=209077}

PostgreSQL per second: commits 4942, WAL flushes 4413, WAL flush time 648 ms (max 804 ms), WAL write time 18 ms

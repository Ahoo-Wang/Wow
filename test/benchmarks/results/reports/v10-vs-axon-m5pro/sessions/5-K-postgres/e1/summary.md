# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /tmp/wow-bench-m5pro/vs-axon/5-K-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.7 ms | 1.6 ms | 3.0 ms | 10.8 ms | 22.0 ms | 17.5 ms |

Load generator: 0.45 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.65 | 1232 MB | 738 MB | 14 | 0.039 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 38.2% | 144.0% | 1.763GiB / 15.6GiB |
| wow-e2e-bench-postgres | 62.7% | 100.5% | 646.8MiB / 15.6GiB |

Store operations, postgres (seconds -0.062 to 40.923): {epoch=40.985673904418945, xact_commit=195624, xact_rollback=0, tup_inserted=100718, tup_updated=222803, tup_deleted=7, tup_fetched=282792, tup_returned=342617, blks_read=0, blks_hit=2509494, wal_records=1027093, wal_fpi=2, wal_bytes=122514655, wal_buffers_full=0, wal_write=170396, wal_sync=170396, wal_write_time=707.682, wal_sync_time=28284.676}
Store operations, kafkaEndOffsets (seconds -19.875 to 41.77): {wow.transfer.account.state=147613}

PostgreSQL per second: commits 4772, WAL flushes 4157, WAL flush time 690 ms (max 769 ms), WAL write time 17 ms

# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-220353-run-postgres-n1-saturation.

Open loop at 1742.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1742.0/s | 104520 | 0 | 5.5 ms | 12.2 ms | 280.6 ms | 364.5 ms | 477.5 ms | 405.5 ms |

Load generator: 0.47 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.40 | 1271 MB | 845 MB | 40 | 0.118 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 47.7% | 167.0% | 2.188GiB / 15.6GiB |
| wow-e2e-bench-postgres | 128.7% | 165.2% | 901.5MiB / 15.6GiB |

Store operations, postgres (seconds -0.055 to 60.946): {epoch=61.00064492225647, xact_commit=238552, xact_rollback=0, tup_inserted=315576, tup_updated=650474, tup_deleted=12, tup_fetched=1349017, tup_returned=2924489, blks_read=304652, blks_hit=12045443, wal_records=2892411, wal_fpi=172, wal_bytes=450575268, wal_buffers_full=0, wal_write=134433, wal_sync=134433, wal_write_time=1122.6579999999994, wal_sync_time=44533.272}
Store operations, kafkaEndOffsets (seconds -30.029 to 62.012): {wow.transfer.account.state=475947}

PostgreSQL per second: commits 3911, WAL flushes 2204, WAL flush time 730 ms (max 918 ms), WAL write time 18 ms

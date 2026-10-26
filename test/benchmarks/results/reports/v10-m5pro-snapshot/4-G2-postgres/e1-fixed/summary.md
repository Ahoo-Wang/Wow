# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 9, settleSeconds 4, accounts 100000, session /Users/noahji/workspace/code/github/Wow-m5pro-bench-1026/test/benchmarks/results/e2e/20261025-231329-run-postgres-n1-saturation.

Open loop at 9128.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 9128.0/s | 547680 | 0 | 5.1 ms | 12.7 ms | 31.0 ms | 113.2 ms | 202.8 ms | 145.4 ms |

Load generator: 2.46 cores of 15 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.63 | 2147 MB | 1602 MB | 35 | 0.166 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 53.2% | 179.0% | 2.064GiB / 15.6GiB |
| wow-e2e-bench-postgres | 157.7% | 200.7% | 973.7MiB / 15.6GiB |

Store operations, postgres (seconds -0.064 to 60.94): {epoch=61.004470109939575, xact_commit=168187, xact_rollback=0, tup_inserted=549146, tup_updated=1061589, tup_deleted=18, tup_fetched=1999959, tup_returned=13501755, blks_read=46282, blks_hit=26737547, wal_records=4772293, wal_fpi=2, wal_bytes=644999832, wal_buffers_full=0, wal_write=103060, wal_sync=103060, wal_write_time=1080.844, wal_sync_time=44483.22099999999}
Store operations, kafkaEndOffsets (seconds -29.823 to 62.029): {wow.transfer.account.state=812312}

PostgreSQL per second: commits 2757, WAL flushes 1689, WAL flush time 729 ms (max 766 ms), WAL write time 18 ms

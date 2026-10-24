# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 2g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/heap2g-runs/heap2g-postgres-up.

Open loop at 1330.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1330.0/s | 79800 | 0 | 1.9 ms | 133.1 ms | 239.6 ms | 376.8 ms | 427.1 ms | 399.4 ms |

Load generator: 0.28 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.84 | 1677 MB | 1601 MB | 13 | 0.073 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 35.7% | 144.5% | 2.17GiB / 15.6GiB |
| wow-e2e-bench-postgres | 94.4% | 157.9% | 982.3MiB / 15.6GiB |

Store operations, postgres (seconds 0.0 to 60.006): {epoch=60.005565881729126, xact_commit=365936, xact_rollback=0, tup_inserted=238565, tup_updated=487219, tup_deleted=0, tup_fetched=718547, tup_returned=733095, blks_read=12249, blks_hit=6214345, wal_records=2165348, wal_fpi=36931, wal_bytes=551226553, wal_buffers_full=0, wal_write=195601, wal_sync=195601, wal_write_time=1106.616, wal_sync_time=49205.22200000001}
Store operations, kafkaEndOffsets (seconds -30.005 to 62.03): {wow.transfer.account.state=356524}

PostgreSQL per second: commits 6098, WAL flushes 3259, WAL flush time 820 ms (max 1039 ms), WAL write time 18 ms

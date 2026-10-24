# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 18, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-postgres-up.

Open loop at 1995.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1909.8/s | 114589 | 5111 | 378.9 ms | 1777.7 ms | 4128.8 ms | 5046.3 ms | 6055.4 ms | 5406.7 ms |

Errors: {http-429-wow.command.busy=5111}

Load generator: 0.64 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.23 | 1183 MB | 874 MB | 122 | 1.310 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 57.9% | 184.5% | 2.187GiB / 15.6GiB |
| wow-e2e-bench-postgres | 327.5% | 496.0% | 817.1MiB / 15.6GiB |

Store operations, postgres (seconds -0.056 to 60.943): {epoch=60.99844288825989, xact_commit=277291, xact_rollback=0, tup_inserted=354846, tup_updated=421036, tup_deleted=0, tup_fetched=8072850, tup_returned=8083398, blks_read=5646824, blks_hit=10746824, wal_records=2418758, wal_fpi=90489, wal_bytes=972978234, wal_buffers_full=0, wal_write=39092, wal_sync=39090, wal_write_time=4238.523000000001, wal_sync_time=30964.60499999998}
Store operations, kafkaEndOffsets (seconds -29.901 to 62.423): {wow.transfer.account.state=529794}

PostgreSQL per second: commits 4546, WAL flushes 641, WAL flush time 508 ms (max 2654 ms), WAL write time 69 ms

# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 18, settleSeconds 4, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-postgres-up.

Open loop at 10356.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 9275.6/s | 556536 | 64824 | 98.3 ms | 712.7 ms | 1392.6 ms | 2342.9 ms | 3061.0 ms | 2424.8 ms |

Errors: {http-429-wow.command.busy=64824}

Load generator: 2.25 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.10 | 1265 MB | 865 MB | 134 | 1.055 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 52.9% | 194.1% | 2.063GiB / 15.6GiB |
| wow-e2e-bench-postgres | 258.5% | 320.7% | 748.8MiB / 15.6GiB |

Store operations, postgres (seconds -0.055 to 60.944): {epoch=60.99878001213074, xact_commit=275017, xact_rollback=0, tup_inserted=558188, tup_updated=604789, tup_deleted=0, tup_fetched=6114059, tup_returned=6123675, blks_read=3319994, blks_hit=15225807, wal_records=3668033, wal_fpi=41472, wal_bytes=836456957, wal_buffers_full=0, wal_write=46180, wal_sync=46179, wal_write_time=1070.728000000001, wal_sync_time=33388.90900000004}
Store operations, kafkaEndOffsets (seconds -30.023 to 62.281): {wow.transfer.account.state=861844}

PostgreSQL per second: commits 4509, WAL flushes 757, WAL flush time 548 ms (max 2314 ms), WAL write time 18 ms

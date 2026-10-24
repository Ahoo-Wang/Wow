# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.1" 2023-10-17 LTS OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS) OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 18, settleSeconds 3, accounts 100000, session /tmp/v10-local-baseline-m4pro/runs/s1-postgres-up.

Open loop at 3752.0/s, zipf over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3569.0/s | 214139 | 10981 | 2.2 ms | 1089.5 ms | 4079.6 ms | 5931.0 ms | 5936.9 ms | 5931.0 ms |

Errors: {http-429-wow.command.busy=10981}

Load generator: 1.02 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.25 | 1166 MB | 814 MB | 37 | 0.211 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 37.4% | 135.7% | 2.241GiB / 15.6GiB |
| wow-e2e-bench-postgres | 94.4% | 145.9% | 857.4MiB / 15.6GiB |

Store operations, postgres (seconds -0.056 to 60.944): {epoch=61.000104904174805, xact_commit=327950, xact_rollback=0, tup_inserted=213876, tup_updated=444385, tup_deleted=0, tup_fetched=964169, tup_returned=990436, blks_read=137781, blks_hit=5333300, wal_records=1955436, wal_fpi=15895, wal_bytes=349494935, wal_buffers_full=0, wal_write=139360, wal_sync=139360, wal_write_time=1075.440999999999, wal_sync_time=48585.95699999994}
Store operations, kafkaEndOffsets (seconds -30.025 to 62.016): {wow.transfer.account.state=293431}

PostgreSQL per second: commits 5376, WAL flushes 2285, WAL flush time 797 ms (max 3291 ms), WAL write time 18 ms

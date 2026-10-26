# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /tmp/wow-vs-axon-c48c5d5191/vs-axon/5-K-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.1 ms | 1.9 ms | 407.6 ms | 749.6 ms | 796.7 ms | 770.0 ms |

Load generator: 0.16 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.24 | 1174 MB | 757 MB | 10 | 0.029 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 34.1% | 117.3% | 1.838GiB / 15.6GiB |
| wow-e2e-bench-postgres | 45.7% | 73.7% | 755.2MiB / 15.6GiB |

Store operations, postgres (seconds -0.058 to 40.943): {epoch=41.00110697746277, xact_commit=166851, xact_rollback=0, tup_inserted=85329, tup_updated=190980, tup_deleted=7, tup_fetched=238068, tup_returned=283615, blks_read=0, blks_hit=2268937, wal_records=858987, wal_fpi=0, wal_bytes=126180232, wal_buffers_full=0, wal_write=163495, wal_sync=163495, wal_write_time=592.8050000000001, wal_sync_time=18690.679999999993}
Store operations, kafkaEndOffsets (seconds -20.032 to 41.815): {wow.transfer.account.state=124754}

PostgreSQL per second: commits 4069, WAL flushes 3987, WAL flush time 456 ms (max 967 ms), WAL write time 14 ms

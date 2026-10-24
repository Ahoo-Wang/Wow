# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 26, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s0-up-1.

Open loop at 2542.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2542.0/s | 152520 | 0 | 5.5 ms | 76.8 ms | 136.2 ms | 202.8 ms | 287.7 ms | 240.6 ms |

Load generator: 0.67 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.10 | 996 MB | 864 MB | 39 | 0.320 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 69.9% | 181.4% | 2.138GiB / 7.748GiB |
| wow-e2e-bench-postgres | 178.4% | 226.7% | 1.034GiB / 7.748GiB |

Store operations, postgres (seconds -0.047 to 60.955): {epoch=61.00191593170166, xact_commit=321267, xact_rollback=0, tup_inserted=153267, tup_updated=327775, tup_deleted=9, tup_fetched=1820033, tup_returned=1886329, blks_read=86536, blks_hit=6169052, wal_records=1427263, wal_fpi=102, wal_bytes=182582256, wal_buffers_full=0, wal_write=96289, wal_sync=96289, wal_write_time=1649.4650000000001, wal_sync_time=50602.091000000015}
Store operations, kafkaEndOffsets (seconds -30.027 to 62.224): {wow.transfer.account.state=225563}

PostgreSQL per second: commits 5266, WAL flushes 1578, WAL flush time 830 ms (max 863 ms), WAL write time 27 ms

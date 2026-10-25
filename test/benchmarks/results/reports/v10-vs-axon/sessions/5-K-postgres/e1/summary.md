# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/c40c0d64-7f27-4258-a64d-34425fc669a4/scratchpad/vs-axon/5-K-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 0.5 ms | 1.9 ms | 4.4 ms | 38.9 ms | 70.0 ms | 50.2 ms |

Load generator: 0.37 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.19 | 1094 MB | 752 MB | 15 | 0.061 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 41.3% | 154.6% | 1.696GiB / 7.748GiB |
| wow-e2e-bench-postgres | 60.3% | 121.9% | 623.9MiB / 7.748GiB |

Store operations, postgres (seconds -0.991 to 40.01): {epoch=41.0001699924469, xact_commit=199258, xact_rollback=0, tup_inserted=102948, tup_updated=227757, tup_deleted=7, tup_fetched=288866, tup_returned=432015, blks_read=0, blks_hit=2655746, wal_records=1049703, wal_fpi=3, wal_bytes=125538766, wal_buffers_full=0, wal_write=183671, wal_sync=183671, wal_write_time=692.289, wal_sync_time=21896.947}
Store operations, kafkaEndOffsets (seconds -19.772 to 41.837): {wow.transfer.account.state=147220}

PostgreSQL per second: commits 4860, WAL flushes 4480, WAL flush time 534 ms (max 697 ms), WAL write time 17 ms

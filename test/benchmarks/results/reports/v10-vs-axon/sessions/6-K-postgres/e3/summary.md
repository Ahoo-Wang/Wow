# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 11, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/c40c0d64-7f27-4258-a64d-34425fc669a4/scratchpad/vs-axon/6-K-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 1.1 ms | 2.8 ms | 5.7 ms | 26.9 ms | 61.7 ms | 50.4 ms |

Load generator: 0.16 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.93 | 876 MB | 754 MB | 9 | 0.040 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.1% | 166.5% | 1.798GiB / 7.748GiB |
| wow-e2e-bench-postgres | 53.6% | 141.4% | 737.7MiB / 7.748GiB |

Store operations, postgres (seconds -0.047 to 40.954): {epoch=41.000648021698, xact_commit=168259, xact_rollback=0, tup_inserted=84915, tup_updated=191842, tup_deleted=7, tup_fetched=237239, tup_returned=284939, blks_read=0, blks_hit=2266901, wal_records=861537, wal_fpi=0, wal_bytes=125907469, wal_buffers_full=0, wal_write=158228, wal_sync=158228, wal_write_time=607.7040000000002, wal_sync_time=19192.278}
Store operations, kafkaEndOffsets (seconds -20.026 to 41.806): {wow.transfer.account.state=124554}

PostgreSQL per second: commits 4104, WAL flushes 3859, WAL flush time 468 ms (max 631 ms), WAL write time 15 ms

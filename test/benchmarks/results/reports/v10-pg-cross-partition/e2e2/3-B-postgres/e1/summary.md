# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 13, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/pgx/e2e2/3-B-postgres/up.

Open loop at 2500.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2500.0/s | 100000 | 0 | 1.1 ms | 3.8 ms | 23.8 ms | 130.6 ms | 175.3 ms | 148.5 ms |

Load generator: 0.52 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.51 | 1124 MB | 746 MB | 14 | 0.068 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 48.4% | 168.5% | 1.73GiB / 7.748GiB |
| wow-e2e-bench-postgres | 90.2% | 149.7% | 628.4MiB / 7.748GiB |

Store operations, postgres (seconds 0.0 to 39.999): {epoch=39.999645948410034, xact_commit=173878, xact_rollback=0, tup_inserted=99899, tup_updated=220662, tup_deleted=7, tup_fetched=291513, tup_returned=355022, blks_read=0, blks_hit=2522330, wal_records=1018145, wal_fpi=3, wal_bytes=122189708, wal_buffers_full=0, wal_write=135182, wal_sync=135182, wal_write_time=976.6859999999997, wal_sync_time=28432.422999999995}
Store operations, kafkaEndOffsets (seconds -19.794 to 41.914): {wow.transfer.account.state=146950}

PostgreSQL per second: commits 4347, WAL flushes 3380, WAL flush time 711 ms (max 776 ms), WAL write time 24 ms

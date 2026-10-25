# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 28, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/sizes/1-A-postgres/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 2.2 ms | 148.5 ms | 798.7 ms | 1228.8 ms | 1656.6 ms | 1351.7 ms |

Load generator: 0.44 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.41 | 842 MB | 831 MB | 19 | 0.360 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 65.8% | 240.8% | 2.122GiB / 7.748GiB |
| wow-e2e-bench-postgres | 130.5% | 380.6% | 791.1MiB / 7.748GiB |

Store operations, postgres (seconds -0.889 to 60.111): {epoch=60.999428033828735, xact_commit=225751, xact_rollback=0, tup_inserted=116096, tup_updated=256098, tup_deleted=0, tup_fetched=1171385, tup_returned=1179518, blks_read=481564, blks_hit=3442411, wal_records=1118648, wal_fpi=17186, wal_bytes=239996356, wal_buffers_full=0, wal_write=122315, wal_sync=122315, wal_write_time=1351.4050000000007, wal_sync_time=43933.97700000001}
Store operations, kafkaEndOffsets (seconds -29.918 to 62.184): {wow.transfer.account.state=168886}

PostgreSQL per second: commits 3700, WAL flushes 2005, WAL flush time 720 ms (max 905 ms), WAL write time 22 ms

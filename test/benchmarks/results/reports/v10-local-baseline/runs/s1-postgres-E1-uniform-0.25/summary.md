# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 41, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s1-postgres-up.

Open loop at 953.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 953.0/s | 57180 | 0 | 0.9 ms | 2.7 ms | 5.5 ms | 15.4 ms | 31.9 ms | 21.5 ms |

Load generator: 0.19 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 0.76 | 1050 MB | 827 MB | 18 | 0.094 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 52.6% | 193.3% | 1.902GiB / 7.748GiB |
| wow-e2e-bench-postgres | 44.5% | 109.3% | 958.2MiB / 7.748GiB |

Store operations, postgres (seconds -0.049 to 60.952): {epoch=61.00068497657776, xact_commit=158510, xact_rollback=0, tup_inserted=57761, tup_updated=147065, tup_deleted=0, tup_fetched=457763, tup_returned=499548, blks_read=0, blks_hit=2174595, wal_records=631352, wal_fpi=13, wal_bytes=72425479, wal_buffers_full=0, wal_write=112744, wal_sync=112744, wal_write_time=660.1589999999997, wal_sync_time=15741.831000000006}
Store operations, kafkaEndOffsets (seconds -29.75 to 62.021): {wow.transfer.account.state=84520}

PostgreSQL per second: commits 2598, WAL flushes 1848, WAL flush time 258 ms (max 524 ms), WAL write time 11 ms

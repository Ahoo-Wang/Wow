# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 21, settleSeconds 3, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/bloat/up.

Open loop at 1906.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1906.0/s | 114360 | 0 | 1.0 ms | 2.3 ms | 4.2 ms | 22.9 ms | 50.5 ms | 37.9 ms |

Load generator: 0.33 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.17 | 1056 MB | 826 MB | 33 | 0.103 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 51.0% | 173.9% | 1.998GiB / 7.748GiB |
| wow-e2e-bench-postgres | 76.0% | 114.6% | 1.015GiB / 7.748GiB |

Store operations, postgres (seconds -0.057 to 60.944): {epoch=61.00059103965759, xact_commit=313945, xact_rollback=0, tup_inserted=115649, tup_updated=263339, tup_deleted=9, tup_fetched=1349956, tup_returned=1397600, blks_read=27750, blks_hit=4692703, wal_records=1162153, wal_fpi=11, wal_bytes=141719242, wal_buffers_full=0, wal_write=204089, wal_sync=204089, wal_write_time=1072.3220000000001, wal_sync_time=33605.33900000001}
Store operations, kafkaEndOffsets (seconds -30.018 to 62.02): {wow.transfer.account.state=169946}

PostgreSQL per second: commits 5147, WAL flushes 3346, WAL flush time 551 ms (max 734 ms), WAL write time 18 ms

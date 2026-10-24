# run: v10 E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 20, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/ab/cache/1-default-postgres/up.

Open loop at 800.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 800.0/s | 48000 | 0 | 1.8 ms | 3.8 ms | 5.6 ms | 30.6 ms | 59.0 ms | 48.1 ms |

Load generator: 0.16 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.28 | 1244 MB | 821 MB | 32 | 0.090 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 47.4% | 167.9% | 2.134GiB / 7.748GiB |
| wow-e2e-bench-postgres | 77.8% | 112.4% | 947.8MiB / 7.748GiB |

Store operations, postgres (seconds -0.058 to 60.948): {epoch=61.00639009475708, xact_commit=353536, xact_rollback=0, tup_inserted=144483, tup_updated=321663, tup_deleted=7, tup_fetched=935778, tup_returned=1007474, blks_read=0, blks_hit=5029862, wal_records=1440580, wal_fpi=47, wal_bytes=207861783, wal_buffers_full=0, wal_write=236810, wal_sync=236810, wal_write_time=987.7559999999999, wal_sync_time=36717.229999999996}
Store operations, kafkaEndOffsets (seconds -30.033 to 61.967): {wow.transfer.account.state=212887}

PostgreSQL per second: commits 5795, WAL flushes 3882, WAL flush time 602 ms (max 792 ms), WAL write time 16 ms

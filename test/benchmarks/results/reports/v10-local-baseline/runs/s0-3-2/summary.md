# run: v10 E1

deposit, answered once stored (9.x PROCESSED, V10 committed); backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 36, settleSeconds 4, accounts 100000, session /private/tmp/claude-501/-Users-worker-work-Wow--claude-worktrees-performance-optimization-327071/1e81896d-3639-40f5-8c4f-ed1ecfc9cafd/scratchpad/baseline/s0-up-3.

Open loop at 2542.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2542.0/s | 152520 | 0 | 12.6 ms | 110.6 ms | 166.9 ms | 224.3 ms | 307.4 ms | 256.0 ms |

Load generator: 0.70 cores of 10 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 2.16 | 1014 MB | 832 MB | 42 | 0.239 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 77.6% | 185.1% | 2.082GiB / 7.748GiB |
| wow-e2e-bench-postgres | 185.9% | 304.6% | 1018MiB / 7.748GiB |

Store operations, postgres (seconds -0.049 to 60.952): {epoch=61.000547885894775, xact_commit=290759, xact_rollback=0, tup_inserted=152669, tup_updated=317654, tup_deleted=7, tup_fetched=1203782, tup_returned=1293265, blks_read=12, blks_hit=5595789, wal_records=1403811, wal_fpi=92, wal_bytes=180531554, wal_buffers_full=0, wal_write=80966, wal_sync=80966, wal_write_time=1712.9099999999999, wal_sync_time=50256.438000000024}
Store operations, kafkaEndOffsets (seconds -30.024 to 62.275): {wow.transfer.account.state=226590}

PostgreSQL per second: commits 4766, WAL flushes 1327, WAL flush time 824 ms (max 865 ms), WAL write time 28 ms

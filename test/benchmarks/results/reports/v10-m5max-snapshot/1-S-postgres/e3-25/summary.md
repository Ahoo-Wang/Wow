# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 4, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/e2e/20261030-045921-run-postgres-n1-saturation.

Open loop at 2275.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2275.0/s | 136500 | 0 | 8.5 ms | 329.7 ms | 581.6 ms | 798.7 ms | 901.9 ms | 843.8 ms |

Load generator: 0.43 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.97 | 1385 MB | 831 MB | 55 | 0.149 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 36.1% | 133.0% | 1.143GiB / 31.36GiB |
| wow-e2e-bench-postgres | 97.8% | 134.4% | 734.9MiB / 31.36GiB |

Store operations, postgres (seconds -0.053 to 60.947): {epoch=61.0004301071167, xact_commit=154847, xact_rollback=0, tup_inserted=410288, tup_updated=763284, tup_deleted=0, tup_fetched=1608961, tup_returned=1617016, blks_read=695833, blks_hit=12899344, wal_records=3604142, wal_fpi=847, wal_bytes=568297483, wal_buffers_full=0, wal_write=60628, wal_sync=60628, wal_write_time=501.54899999999907, wal_sync_time=55271.601999999955}
Store operations, kafkaEndOffsets (seconds -30.039 to 61.939): {wow.transfer.account.state=617116, wow.transfer.commands=0}

PostgreSQL per second: commits 2539, WAL flushes 994, WAL flush time 906 ms (max 1309 ms), WAL write time 8 ms

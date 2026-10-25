# run: E1

deposit, answered once stored; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 7, settleSeconds 4, accounts 100000, session /Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-snapshot-2/test/benchmarks/results/e2e/20261025-101113-run-postgres-n1-saturation.

Open loop at 10503.0/s, uniform over 100000 accounts, 30 s warm-up, 60 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10381.2/s | 622872 | 7308 | 5.1 ms | 51.2 ms | 143.4 ms | 1097.7 ms | 1243.2 ms | 1179.6 ms |

Errors: {http-429-wow.command.busy=7308}

Load generator: 2.57 cores of 14 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 3.20 | 1197 MB | 838 MB | 84 | 0.405 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 43.3% | 135.7% | 2.018GiB / 15.6GiB |
| wow-e2e-bench-postgres | 128.7% | 164.1% | 962.1MiB / 15.6GiB |

Store operations, postgres (seconds -0.995 to 60.004): {epoch=60.999261140823364, xact_commit=206751, xact_rollback=0, tup_inserted=627644, tup_updated=1183730, tup_deleted=7, tup_fetched=2236800, tup_returned=6507283, blks_read=266, blks_hit=20152787, wal_records=5435364, wal_fpi=2, wal_bytes=737445040, wal_buffers_full=0, wal_write=130008, wal_sync=130008, wal_write_time=1119.5810000000001, wal_sync_time=39315.008}
Store operations, kafkaEndOffsets (seconds -29.695 to 62.18): {wow.transfer.account.state=926360}

PostgreSQL per second: commits 3389, WAL flushes 2131, WAL flush time 645 ms (max 1327 ms), WAL write time 18 ms

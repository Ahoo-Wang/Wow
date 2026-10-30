# run: E3

transfer, answered once the saga's entry is stored on the target; backend postgres, nodes 1, heap 1g, gc G1, serverJvm openjdk version "21.0.13" 2026-10-20 LTS OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing), stores kafka postgres, primed true, primeSeconds 8, settleSeconds 3, accounts 100000, session /Users/ahoowang/.gemini/antigravity/worktrees/Wow/v10_m5max_benchmark_snapshot/test/benchmarks/results/reports/v10-vs-axon-m5max/sessions/6-K-postgres/up.

Open loop at 700.0/s, uniform over 100000 accounts, 20 s warm-up, 40 s measured.

| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 700.0/s | 28000 | 0 | 2.3 ms | 4.3 ms | 7.6 ms | 20.2 ms | 33.0 ms | 21.6 ms |

Load generator: 0.12 cores of 18 available to it.

| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |
| --- | --- | --- | --- | --- | --- |
| node-1 | 1.13 | 1284 MB | 732 MB | 19 | 0.032 s |

| Container | CPU avg | CPU max | Memory |
| --- | --- | --- | --- |
| wow-e2e-bench-kafka | 30.2% | 108.0% | 962.5MiB / 31.36GiB |
| wow-e2e-bench-postgres | 50.2% | 80.4% | 728.4MiB / 31.36GiB |

Store operations, postgres (seconds -0.047 to 40.963): {epoch=41.0100040435791, xact_commit=161192, xact_rollback=0, tup_inserted=84804, tup_updated=192136, tup_deleted=7, tup_fetched=245959, tup_returned=290308, blks_read=0, blks_hit=2280175, wal_records=859078, wal_fpi=0, wal_bytes=123368217, wal_buffers_full=0, wal_write=83244, wal_sync=83244, wal_write_time=321.94899999999996, wal_sync_time=37508.175}
Store operations, kafkaEndOffsets (seconds -20.042 to 41.8): {wow.transfer.account.state=124802, wow.transfer.commands=0}

PostgreSQL per second: commits 3931, WAL flushes 2030, WAL flush time 915 ms (max 947 ms), WAL write time 8 ms

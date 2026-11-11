# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `main` 44eafe932db53b2a3bfe38861ea92f8cc2ef2a4f; V10 baseline: `v10.0.0-beta.1` 22f8ac19cb50b73d90613d7d401257600b3a914f; harness and load generator from head.

Tier: custom; coverage: component-mongo@100k, component-pg@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| component-mongo@100k | e1-head | counts | **more store round trips** | 0.21 against 0.19 per operation (×1.12) |
| component-mongo@100k | e3-head | counts | **more store round trips** | 0.55 against 0.50 per operation (×1.11) |
| component-mongo@100k | e1-large-head | counts | **more store round trips** | 0.28 against 0.25 per operation (×1.14) |
| component-pg@100k | e1-head | counts | **more store round trips** | 0.62 against 0.51 per operation (×1.22) |
| component-pg@100k | e1-head | counts | **more allocation** | 66384.59 against 62524.25 per operation (×1.06) |
| component-pg@100k | e3-head | counts | **more store round trips** | 1.77 against 1.44 per operation (×1.22) |
| component-pg@100k | e3-head | counts | **more allocation** | 216488.51 against 198963.37 per operation (×1.09) |
| component-pg@100k | e1-large-head | counts | **more store round trips** | 0.85 against 0.67 per operation (×1.28) |

## L1 component counts

The domain on the kernel and one store in one JVM, no HTTP: each operation's store round trips (the node's background work over the measured phase included) and bytes allocated. Counts compare anywhere; the timings only indicate.

| Suite | Scenario | Arm | Store round trips/op | By command | Allocated/op | Throughput (indicative) | p50 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| component-mongo@100k | E1 | base | 0.19 | aggregate 0.00 · find 0.00 · findAndModify 0.00 · insert 0.07 · ping 0.00 · update 0.11 | 72 KiB | 4180/s | 12.9 ms | 58.9 ms |
| component-mongo@100k | E1 | head | 0.21 | aggregate 0.00 · find 0.01 · findAndModify 0.01 · insert 0.07 · update 0.13 | 74 KiB | 3912/s | 13.3 ms | 57.5 ms |
| component-mongo@100k | E3 | head | 0.55 | aggregate 0.00 · find 0.02 · findAndModify 0.02 · insert 0.18 · ping 0.00 · update 0.32 | 237 KiB | 1300/s | 43.1 ms | 135.5 ms |
| component-mongo@100k | E3 | base | 0.50 | aggregate 0.00 · find 0.00 · findAndModify 0.01 · insert 0.18 · ping 0.00 · update 0.30 | 230 KiB | 1368/s | 40.4 ms | 141.5 ms |
| component-mongo@100k | E1-large | base | 0.25 | aggregate 0.00 · find 0.00 · findAndModify 0.00 · insert 0.08 · ping 0.00 · update 0.16 | 182 KiB | 2909/s | 18.8 ms | 74.4 ms |
| component-mongo@100k | E1-large | head | 0.28 | aggregate 0.00 · find 0.01 · findAndModify 0.01 · insert 0.08 · ping 0.00 · update 0.18 | 185 KiB | 2563/s | 21.1 ms | 90.0 ms |
| component-pg@100k | E1 | base | 0.51 | commit 0.21 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.00 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.21 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.09 | 61 KiB | 2479/s | 15.6 ms | 210.9 ms |
| component-pg@100k | E1 | head | 0.62 | commit 0.25 · statement insert wow_lease 0.00 · statement insert wow_lease_member 0.00 · statement insert wow_snapshot__transfer__account 0.01 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__account 0.01 · statement select wow_log_aggregate 0.00 · statement select wow_snapshot_table 0.01 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.25 · statement update wow_lease 0.00 · statement update wow_log__transfer__account 0.01 · statement update wow_log_aggregate 0.00 · statement with wow_log__transfer__account 0.10 | 65 KiB | 2848/s | 15.5 ms | 195.4 ms |
| component-pg@100k | E3 | head | 1.77 | commit 0.72 · statement insert wow_lease 0.00 · statement insert wow_lease_member 0.00 · statement insert wow_snapshot__transfer__account 0.01 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__account 0.01 · statement select wow_log__transfer__partition 0.01 · statement select wow_log_aggregate 0.00 · statement select wow_snapshot_table 0.01 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.72 · statement update wow_lease 0.00 · statement update wow_log__transfer__account 0.01 · statement update wow_log_aggregate 0.00 · statement with wow_log__transfer__account 0.27 | 211 KiB | 925/s | 51.8 ms | 311.7 ms |
| component-pg@100k | E3 | base | 1.44 | commit 0.58 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.58 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.26 | 194 KiB | 1077/s | 48.1 ms | 210.3 ms |
| component-pg@100k | E1-large | base | 0.67 | commit 0.28 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.28 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.10 | 187 KiB | 1184/s | 38.5 ms | 266.4 ms |
| component-pg@100k | E1-large | head | 0.85 | commit 0.35 · statement insert wow_lease 0.00 · statement insert wow_lease_member 0.00 · statement insert wow_snapshot__transfer__account 0.01 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__account 0.01 · statement select wow_log__transfer__partition 0.01 · statement select wow_log_aggregate 0.00 · statement select wow_snapshot_table 0.01 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.35 · statement update wow_lease 0.00 · statement update wow_log__transfer__account 0.01 · statement update wow_log_aggregate 0.00 · statement with wow_log__transfer__account 0.10 | 192 KiB | 1289/s | 37.0 ms | 215.8 ms |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| component-mongo@100k | AMD EPYC 9V45 96-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |
| component-pg@100k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |

---

### component-mongo@100k

L1 on MongoDB: the domain on the kernel and the store in one JVM, no HTTP; each operation's store round trips by command and bytes allocated, head against baseline when there is one.

scenarios below, undefined keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V45 96-Core Processor, 4 cores.

| Suite | Scenario | Arm | Store round trips/op | By command | Allocated/op | Throughput (indicative) | p50 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| component-mongo@100k | E1 | base | 0.19 | aggregate 0.00 · find 0.00 · findAndModify 0.00 · insert 0.07 · ping 0.00 · update 0.11 | 72 KiB | 4180/s | 12.9 ms | 58.9 ms |
| component-mongo@100k | E1 | head | 0.21 | aggregate 0.00 · find 0.01 · findAndModify 0.01 · insert 0.07 · update 0.13 | 74 KiB | 3912/s | 13.3 ms | 57.5 ms |
| component-mongo@100k | E3 | head | 0.55 | aggregate 0.00 · find 0.02 · findAndModify 0.02 · insert 0.18 · ping 0.00 · update 0.32 | 237 KiB | 1300/s | 43.1 ms | 135.5 ms |
| component-mongo@100k | E3 | base | 0.50 | aggregate 0.00 · find 0.00 · findAndModify 0.01 · insert 0.18 · ping 0.00 · update 0.30 | 230 KiB | 1368/s | 40.4 ms | 141.5 ms |
| component-mongo@100k | E1-large | base | 0.25 | aggregate 0.00 · find 0.00 · findAndModify 0.00 · insert 0.08 · ping 0.00 · update 0.16 | 182 KiB | 2909/s | 18.8 ms | 74.4 ms |
| component-mongo@100k | E1-large | head | 0.28 | aggregate 0.00 · find 0.01 · findAndModify 0.01 · insert 0.08 · ping 0.00 · update 0.18 | 185 KiB | 2563/s | 21.1 ms | 90.0 ms |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| component-mongo@100k | e1-head | counts | **more store round trips** | 0.21 against 0.19 per operation (×1.12) |
| component-mongo@100k | e3-head | counts | **more store round trips** | 0.55 against 0.50 per operation (×1.11) |
| component-mongo@100k | e1-large-head | counts | **more store round trips** | 0.28 against 0.25 per operation (×1.14) |


### component-pg@100k

L1 on PostgreSQL, as component-mongo (statements, commits and rollbacks).

scenarios below, undefined keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

| Suite | Scenario | Arm | Store round trips/op | By command | Allocated/op | Throughput (indicative) | p50 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| component-pg@100k | E1 | base | 0.51 | commit 0.21 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.00 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.21 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.09 | 61 KiB | 2479/s | 15.6 ms | 210.9 ms |
| component-pg@100k | E1 | head | 0.62 | commit 0.25 · statement insert wow_lease 0.00 · statement insert wow_lease_member 0.00 · statement insert wow_snapshot__transfer__account 0.01 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__account 0.01 · statement select wow_log_aggregate 0.00 · statement select wow_snapshot_table 0.01 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.25 · statement update wow_lease 0.00 · statement update wow_log__transfer__account 0.01 · statement update wow_log_aggregate 0.00 · statement with wow_log__transfer__account 0.10 | 65 KiB | 2848/s | 15.5 ms | 195.4 ms |
| component-pg@100k | E3 | head | 1.77 | commit 0.72 · statement insert wow_lease 0.00 · statement insert wow_lease_member 0.00 · statement insert wow_snapshot__transfer__account 0.01 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__account 0.01 · statement select wow_log__transfer__partition 0.01 · statement select wow_log_aggregate 0.00 · statement select wow_snapshot_table 0.01 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.72 · statement update wow_lease 0.00 · statement update wow_log__transfer__account 0.01 · statement update wow_log_aggregate 0.00 · statement with wow_log__transfer__account 0.27 | 211 KiB | 925/s | 51.8 ms | 311.7 ms |
| component-pg@100k | E3 | base | 1.44 | commit 0.58 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.58 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.26 | 194 KiB | 1077/s | 48.1 ms | 210.3 ms |
| component-pg@100k | E1-large | base | 0.67 | commit 0.28 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.28 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.10 | 187 KiB | 1184/s | 38.5 ms | 266.4 ms |
| component-pg@100k | E1-large | head | 0.85 | commit 0.35 · statement insert wow_lease 0.00 · statement insert wow_lease_member 0.00 · statement insert wow_snapshot__transfer__account 0.01 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__account 0.01 · statement select wow_log__transfer__partition 0.01 · statement select wow_log_aggregate 0.00 · statement select wow_snapshot_table 0.01 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.35 · statement update wow_lease 0.00 · statement update wow_log__transfer__account 0.01 · statement update wow_log_aggregate 0.00 · statement with wow_log__transfer__account 0.10 | 192 KiB | 1289/s | 37.0 ms | 215.8 ms |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| component-pg@100k | e1-head | counts | **more store round trips** | 0.62 against 0.51 per operation (×1.22) |
| component-pg@100k | e1-head | counts | **more allocation** | 66384.59 against 62524.25 per operation (×1.06) |
| component-pg@100k | e3-head | counts | **more store round trips** | 1.77 against 1.44 per operation (×1.22) |
| component-pg@100k | e3-head | counts | **more allocation** | 216488.51 against 198963.37 per operation (×1.09) |
| component-pg@100k | e1-large-head | counts | **more store round trips** | 0.85 against 0.67 per operation (×1.28) |



# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `e1fa5a04f139a747a0ab1a55b7482c53af0af3e1` e1fa5a04f139a747a0ab1a55b7482c53af0af3e1; V10 baseline: `v10.0.0-beta.1` 22f8ac19cb50b73d90613d7d401257600b3a914f; harness and load generator from head.

Tier: custom; coverage: component-mongo@100k, component-pg@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| component-mongo@100k | e1-head | counts | **more store round trips** | 0.20 against 0.19 per operation (×1.06) |
| component-mongo@100k | e3-head | counts | **more store round trips** | 0.54 against 0.51 per operation (×1.06) |
| component-mongo@100k | e1-large-head | counts | **more store round trips** | 0.29 against 0.27 per operation (×1.08) |
| component-pg@100k | e1-head | counts | **more store round trips** | 0.63 against 0.49 per operation (×1.26) |
| component-pg@100k | e1-head | counts | **more allocation** | 66142.44 against 61382.81 per operation (×1.08) |
| component-pg@100k | e3-head | counts | **more store round trips** | 1.74 against 1.44 per operation (×1.21) |
| component-pg@100k | e3-head | counts | **more allocation** | 216899.48 against 199624.47 per operation (×1.09) |
| component-pg@100k | e1-large-head | counts | **more store round trips** | 0.79 against 0.71 per operation (×1.11) |

## L1 component counts

The domain on the kernel and one store in one JVM, no HTTP: each operation's store round trips (the node's background work over the measured phase included) and bytes allocated. Counts compare anywhere; the timings only indicate.

| Suite | Scenario | Arm | Store round trips/op | By command | Allocated/op | Throughput (indicative) | p50 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| component-mongo@100k | E1 | base | 0.19 | aggregate 0.00 · find 0.00 · findAndModify 0.00 · insert 0.07 · ping 0.00 · update 0.11 | 72 KiB | 2301/s | 18.2 ms | 210.0 ms |
| component-mongo@100k | E1 | head | 0.20 | aggregate 0.00 · find 0.01 · findAndModify 0.01 · insert 0.07 · ping 0.00 · update 0.11 | 73 KiB | 3130/s | 16.3 ms | 96.8 ms |
| component-mongo@100k | E3 | head | 0.54 | aggregate 0.01 · find 0.02 · findAndModify 0.02 · insert 0.19 · ping 0.00 · update 0.30 | 238 KiB | 1049/s | 50.5 ms | 227.9 ms |
| component-mongo@100k | E3 | base | 0.51 | aggregate 0.01 · find 0.00 · findAndModify 0.01 · insert 0.19 · ping 0.00 · update 0.29 | 231 KiB | 927/s | 57.9 ms | 232.7 ms |
| component-mongo@100k | E1-large | base | 0.27 | aggregate 0.01 · find 0.00 · findAndModify 0.00 · insert 0.08 · ping 0.00 · update 0.17 | 184 KiB | 1571/s | 31.0 ms | 278.9 ms |
| component-mongo@100k | E1-large | head | 0.29 | aggregate 0.00 · find 0.01 · findAndModify 0.01 · insert 0.09 · ping 0.00 · update 0.18 | 185 KiB | 1830/s | 26.4 ms | 171.4 ms |
| component-pg@100k | E1 | base | 0.49 | commit 0.20 · statement insert wow_lease_member 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.20 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.09 | 60 KiB | 2593/s | 18.2 ms | 137.9 ms |
| component-pg@100k | E1 | head | 0.63 | commit 0.25 · statement insert wow_lease 0.00 · statement insert wow_lease_member 0.00 · statement insert wow_snapshot__transfer__account 0.01 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__account 0.01 · statement select wow_log__transfer__partition 0.00 · statement select wow_log_aggregate 0.00 · statement select wow_snapshot_table 0.01 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.25 · statement update wow_lease 0.00 · statement update wow_log__transfer__account 0.01 · statement update wow_log_aggregate 0.00 · statement with wow_log__transfer__account 0.10 | 65 KiB | 2754/s | 16.8 ms | 102.0 ms |
| component-pg@100k | E3 | head | 1.74 | commit 0.70 · statement insert wow_lease 0.00 · statement insert wow_lease_member 0.00 · statement insert wow_snapshot__transfer__account 0.02 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__account 0.02 · statement select wow_log__transfer__partition 0.01 · statement select wow_log_aggregate 0.00 · statement select wow_snapshot_table 0.02 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.69 · statement update wow_lease 0.00 · statement update wow_log__transfer__account 0.02 · statement update wow_log_aggregate 0.00 · statement with wow_log__transfer__account 0.27 | 212 KiB | 949/s | 50.5 ms | 296.1 ms |
| component-pg@100k | E3 | base | 1.44 | commit 0.58 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.58 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.26 | 195 KiB | 935/s | 49.5 ms | 359.1 ms |
| component-pg@100k | E1-large | base | 0.71 | commit 0.30 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.30 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.10 | 188 KiB | 1409/s | 38.0 ms | 150.3 ms |
| component-pg@100k | E1-large | head | 0.79 | commit 0.33 · statement insert wow_lease_member 0.00 · statement insert wow_snapshot__transfer__account 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__account 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.33 · statement update wow_lease 0.00 · statement update wow_log__transfer__account 0.00 · statement with wow_log__transfer__account 0.10 | 192 KiB | 1198/s | 36.8 ms | 222.0 ms |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| component-mongo@100k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |
| component-pg@100k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |

---

### component-mongo@100k

L1 on MongoDB: the domain on the kernel and the store in one JVM, no HTTP; each operation's store round trips by command and bytes allocated, head against baseline when there is one.

scenarios below, undefined keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

| Suite | Scenario | Arm | Store round trips/op | By command | Allocated/op | Throughput (indicative) | p50 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| component-mongo@100k | E1 | base | 0.19 | aggregate 0.00 · find 0.00 · findAndModify 0.00 · insert 0.07 · ping 0.00 · update 0.11 | 72 KiB | 2301/s | 18.2 ms | 210.0 ms |
| component-mongo@100k | E1 | head | 0.20 | aggregate 0.00 · find 0.01 · findAndModify 0.01 · insert 0.07 · ping 0.00 · update 0.11 | 73 KiB | 3130/s | 16.3 ms | 96.8 ms |
| component-mongo@100k | E3 | head | 0.54 | aggregate 0.01 · find 0.02 · findAndModify 0.02 · insert 0.19 · ping 0.00 · update 0.30 | 238 KiB | 1049/s | 50.5 ms | 227.9 ms |
| component-mongo@100k | E3 | base | 0.51 | aggregate 0.01 · find 0.00 · findAndModify 0.01 · insert 0.19 · ping 0.00 · update 0.29 | 231 KiB | 927/s | 57.9 ms | 232.7 ms |
| component-mongo@100k | E1-large | base | 0.27 | aggregate 0.01 · find 0.00 · findAndModify 0.00 · insert 0.08 · ping 0.00 · update 0.17 | 184 KiB | 1571/s | 31.0 ms | 278.9 ms |
| component-mongo@100k | E1-large | head | 0.29 | aggregate 0.00 · find 0.01 · findAndModify 0.01 · insert 0.09 · ping 0.00 · update 0.18 | 185 KiB | 1830/s | 26.4 ms | 171.4 ms |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| component-mongo@100k | e1-head | counts | **more store round trips** | 0.20 against 0.19 per operation (×1.06) |
| component-mongo@100k | e3-head | counts | **more store round trips** | 0.54 against 0.51 per operation (×1.06) |
| component-mongo@100k | e1-large-head | counts | **more store round trips** | 0.29 against 0.27 per operation (×1.08) |


### component-pg@100k

L1 on PostgreSQL, as component-mongo (statements, commits and rollbacks).

scenarios below, undefined keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

| Suite | Scenario | Arm | Store round trips/op | By command | Allocated/op | Throughput (indicative) | p50 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| component-pg@100k | E1 | base | 0.49 | commit 0.20 · statement insert wow_lease_member 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.20 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.09 | 60 KiB | 2593/s | 18.2 ms | 137.9 ms |
| component-pg@100k | E1 | head | 0.63 | commit 0.25 · statement insert wow_lease 0.00 · statement insert wow_lease_member 0.00 · statement insert wow_snapshot__transfer__account 0.01 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__account 0.01 · statement select wow_log__transfer__partition 0.00 · statement select wow_log_aggregate 0.00 · statement select wow_snapshot_table 0.01 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.25 · statement update wow_lease 0.00 · statement update wow_log__transfer__account 0.01 · statement update wow_log_aggregate 0.00 · statement with wow_log__transfer__account 0.10 | 65 KiB | 2754/s | 16.8 ms | 102.0 ms |
| component-pg@100k | E3 | head | 1.74 | commit 0.70 · statement insert wow_lease 0.00 · statement insert wow_lease_member 0.00 · statement insert wow_snapshot__transfer__account 0.02 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__account 0.02 · statement select wow_log__transfer__partition 0.01 · statement select wow_log_aggregate 0.00 · statement select wow_snapshot_table 0.02 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.69 · statement update wow_lease 0.00 · statement update wow_log__transfer__account 0.02 · statement update wow_log_aggregate 0.00 · statement with wow_log__transfer__account 0.27 | 212 KiB | 949/s | 50.5 ms | 296.1 ms |
| component-pg@100k | E3 | base | 1.44 | commit 0.58 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.58 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.26 | 195 KiB | 935/s | 49.5 ms | 359.1 ms |
| component-pg@100k | E1-large | base | 0.71 | commit 0.30 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.30 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.10 | 188 KiB | 1409/s | 38.0 ms | 150.3 ms |
| component-pg@100k | E1-large | head | 0.79 | commit 0.33 · statement insert wow_lease_member 0.00 · statement insert wow_snapshot__transfer__account 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__account 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.33 · statement update wow_lease 0.00 · statement update wow_log__transfer__account 0.00 · statement with wow_log__transfer__account 0.10 | 192 KiB | 1198/s | 36.8 ms | 222.0 ms |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| component-pg@100k | e1-head | counts | **more store round trips** | 0.63 against 0.49 per operation (×1.26) |
| component-pg@100k | e1-head | counts | **more allocation** | 66142.44 against 61382.81 per operation (×1.08) |
| component-pg@100k | e3-head | counts | **more store round trips** | 1.74 against 1.44 per operation (×1.21) |
| component-pg@100k | e3-head | counts | **more allocation** | 216899.48 against 199624.47 per operation (×1.09) |
| component-pg@100k | e1-large-head | counts | **more store round trips** | 0.79 against 0.71 per operation (×1.11) |



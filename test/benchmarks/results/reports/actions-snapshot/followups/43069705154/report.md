# V10 end-to-end benchmark (D224) on GitHub Actions

Head: `16a04c9e3e1f42f3d6fe7b27d5939b8d8e2b2c3c` 16a04c9e3e1f42f3d6fe7b27d5939b8d8e2b2c3c; V10 baseline: `v10.0.0-beta.1` 22f8ac19cb50b73d90613d7d401257600b3a914f; harness and load generator from head.

Tier: custom; coverage: component-mongo@100k, component-pg@100k.

Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.

## Anomalies

Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than 1% errors, a measured second below half the rate, p99 above 10× p50 (when p99 ≥ 50 ms), a head allocating more than 5.0% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| component-mongo@100k | e3-head | counts | **more store round trips** | 0.53 against 0.48 per operation (×1.09) |
| component-mongo@100k | e3-head | counts | **more allocation** | 246745.09 against 234403.08 per operation (×1.05) |
| component-mongo@100k | e1-large-head | counts | **more store round trips** | 0.28 against 0.26 per operation (×1.09) |
| component-pg@100k | e1-head | counts | **more store round trips** | 0.58 against 0.53 per operation (×1.09) |
| component-pg@100k | e3-head | counts | **more store round trips** | 1.61 against 1.45 per operation (×1.11) |
| component-pg@100k | e3-head | counts | **more allocation** | 216820.64 against 199491.14 per operation (×1.09) |
| component-pg@100k | e1-large-head | counts | **more store round trips** | 0.78 against 0.70 per operation (×1.12) |

## L1 component counts

The domain on the kernel and one store in one JVM, no HTTP: each operation's store round trips (the node's background work over the measured phase included) and bytes allocated. Counts compare anywhere; the timings only indicate.

| Suite | Scenario | Arm | Store round trips/op | By command | Allocated/op | Throughput (indicative) | p50 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| component-mongo@100k | E1 | base | 0.18 | aggregate 0.00 · find 0.00 · findAndModify 0.00 · insert 0.07 · ping 0.00 · update 0.10 | 72 KiB | 2198/s | 24.8 ms | 90.6 ms |
| component-mongo@100k | E1 | head | 0.19 | aggregate 0.00 · find 0.00 · findAndModify 0.00 · insert 0.07 · ping 0.00 · update 0.11 | 74 KiB | 2617/s | 18.7 ms | 97.8 ms |
| component-mongo@100k | E3 | head | 0.53 | aggregate 0.01 · find 0.01 · findAndModify 0.01 · insert 0.19 · ping 0.00 · update 0.31 | 241 KiB | 838/s | 65.9 ms | 212.3 ms |
| component-mongo@100k | E3 | base | 0.48 | aggregate 0.01 · find 0.00 · findAndModify 0.01 · insert 0.19 · ping 0.00 · update 0.27 | 229 KiB | 857/s | 66.1 ms | 207.4 ms |
| component-mongo@100k | E1-large | base | 0.26 | aggregate 0.00 · find 0.00 · findAndModify 0.00 · insert 0.08 · ping 0.00 · update 0.16 | 183 KiB | 1643/s | 33.0 ms | 142.5 ms |
| component-mongo@100k | E1-large | head | 0.28 | aggregate 0.01 · find 0.00 · findAndModify 0.00 · insert 0.09 · ping 0.00 · update 0.18 | 186 KiB | 1508/s | 34.3 ms | 204.2 ms |
| component-pg@100k | E1 | base | 0.53 | commit 0.21 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.00 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.21 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.10 | 61 KiB | 3462/s | 15.6 ms | 71.8 ms |
| component-pg@100k | E1 | head | 0.58 | commit 0.24 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.00 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.24 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.10 | 64 KiB | 3153/s | 16.6 ms | 117.1 ms |
| component-pg@100k | E3 | head | 1.61 | commit 0.66 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.00 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.66 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.27 | 212 KiB | 1232/s | 44.4 ms | 169.1 ms |
| component-pg@100k | E3 | base | 1.45 | commit 0.59 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.59 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.27 | 195 KiB | 1133/s | 45.7 ms | 239.4 ms |
| component-pg@100k | E1-large | base | 0.70 | commit 0.29 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.00 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.29 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.10 | 188 KiB | 1413/s | 37.1 ms | 206.8 ms |
| component-pg@100k | E1-large | head | 0.78 | commit 0.33 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.33 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.10 | 192 KiB | 1074/s | 37.2 ms | 436.0 ms |

## Runners

| Suite | CPU | Cores | Memory | JDK | CPU layout |
| --- | --- | --- | --- | --- | --- |
| component-mongo@100k | INTEL(R) XEON(R) PLATINUM 8573C | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |
| component-pg@100k | AMD EPYC 9V74 80-Core Processor | 4 | 16 GB | Eclipse Adoptium 25.0.5+8-LTS | generator 3 · node 0-2 |

## Saturation points (closed loop)

| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- | --- |

---

### component-mongo@100k

L1 on MongoDB: the domain on the kernel and the store in one JVM, no HTTP; each operation's store round trips by command and bytes allocated, head against baseline when there is one.

scenarios below, undefined keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: INTEL(R) XEON(R) PLATINUM 8573C, 4 cores.

| Suite | Scenario | Arm | Store round trips/op | By command | Allocated/op | Throughput (indicative) | p50 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| component-mongo@100k | E1 | base | 0.18 | aggregate 0.00 · find 0.00 · findAndModify 0.00 · insert 0.07 · ping 0.00 · update 0.10 | 72 KiB | 2198/s | 24.8 ms | 90.6 ms |
| component-mongo@100k | E1 | head | 0.19 | aggregate 0.00 · find 0.00 · findAndModify 0.00 · insert 0.07 · ping 0.00 · update 0.11 | 74 KiB | 2617/s | 18.7 ms | 97.8 ms |
| component-mongo@100k | E3 | head | 0.53 | aggregate 0.01 · find 0.01 · findAndModify 0.01 · insert 0.19 · ping 0.00 · update 0.31 | 241 KiB | 838/s | 65.9 ms | 212.3 ms |
| component-mongo@100k | E3 | base | 0.48 | aggregate 0.01 · find 0.00 · findAndModify 0.01 · insert 0.19 · ping 0.00 · update 0.27 | 229 KiB | 857/s | 66.1 ms | 207.4 ms |
| component-mongo@100k | E1-large | base | 0.26 | aggregate 0.00 · find 0.00 · findAndModify 0.00 · insert 0.08 · ping 0.00 · update 0.16 | 183 KiB | 1643/s | 33.0 ms | 142.5 ms |
| component-mongo@100k | E1-large | head | 0.28 | aggregate 0.01 · find 0.00 · findAndModify 0.00 · insert 0.09 · ping 0.00 · update 0.18 | 186 KiB | 1508/s | 34.3 ms | 204.2 ms |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| component-mongo@100k | e3-head | counts | **more store round trips** | 0.53 against 0.48 per operation (×1.09) |
| component-mongo@100k | e3-head | counts | **more allocation** | 246745.09 against 234403.08 per operation (×1.05) |
| component-mongo@100k | e1-large-head | counts | **more store round trips** | 0.28 against 0.26 per operation (×1.09) |


### component-pg@100k

L1 on PostgreSQL, as component-mongo (statements, commits and rollbacks).

scenarios below, undefined keys over 100000 accounts; warm-up 30 s, measured 40 s. Runner: AMD EPYC 9V74 80-Core Processor, 4 cores.

| Suite | Scenario | Arm | Store round trips/op | By command | Allocated/op | Throughput (indicative) | p50 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| component-pg@100k | E1 | base | 0.53 | commit 0.21 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.00 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.21 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.10 | 61 KiB | 3462/s | 15.6 ms | 71.8 ms |
| component-pg@100k | E1 | head | 0.58 | commit 0.24 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.00 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.24 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.10 | 64 KiB | 3153/s | 16.6 ms | 117.1 ms |
| component-pg@100k | E3 | head | 1.61 | commit 0.66 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.00 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.66 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.27 | 212 KiB | 1232/s | 44.4 ms | 169.1 ms |
| component-pg@100k | E3 | base | 1.45 | commit 0.59 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.59 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.27 | 195 KiB | 1133/s | 45.7 ms | 239.4 ms |
| component-pg@100k | E1-large | base | 0.70 | commit 0.29 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.00 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.29 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.10 | 188 KiB | 1413/s | 37.1 ms | 206.8 ms |
| component-pg@100k | E1-large | head | 0.78 | commit 0.33 · statement insert wow_lease_member 0.00 · statement select wow_failure_ledger 0.00 · statement select wow_lease 0.00 · statement select wow_lease_member 0.00 · statement select wow_log__transfer__partition 0.01 · statement select wow_snapshot_table 0.00 · statement select wow_timer_aggregate 0.00 · statement set wow_checkpoint 0.00 · statement set wow_snapshot__transfer__account 0.33 · statement update wow_lease 0.00 · statement with wow_log__transfer__account 0.10 | 192 KiB | 1074/s | 37.2 ms | 436.0 ms |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| component-pg@100k | e1-head | counts | **more store round trips** | 0.58 against 0.53 per operation (×1.09) |
| component-pg@100k | e3-head | counts | **more store round trips** | 1.61 against 1.45 per operation (×1.11) |
| component-pg@100k | e3-head | counts | **more allocation** | 216820.64 against 199491.14 per operation (×1.09) |
| component-pg@100k | e1-large-head | counts | **more store round trips** | 0.78 against 0.70 per operation (×1.12) |



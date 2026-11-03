# Benchmark reports

Every report names the commit, command, environment and raw results that reproduce it. How the benchmark is run and read is in [the benchmark README](../../README.md).

Before GA the tree keeps the latest report of each kind on each environment (decision D391): two environments, the local Apple M5 Max and GitHub Actions, and two kinds, a snapshot and the comparison with Axon Framework 4. When a new report merges, the one it replaces moves out in the same pull request. A node's raw logs are not committed, unless they are the evidence for a conclusion.

## Current

| | Snapshot | Axon comparison |
| --- | --- | --- |
| Local, Apple M5 Max | [v10-m5max-snapshot-2](v10-m5max-snapshot-2.md): the latest saturation snapshot at `30d0c0348e`. Single-node ceilings on disk (PostgreSQL and MongoDB), the three-node concurrency conflicts (X-34) and the in-memory transport under overload (X-40), against the first M5 Max snapshot. The node logs are not in the tree; the report links them at commit `1357642891`. Replaced when M5 Max snapshot 3 merges | To be measured: the M5 Max rerun of [#5334](https://github.com/Ahoo-Wang/Wow/pull/5334), after the first round's Wow sessions were void (X-33) |
| GitHub Actions | [v10-actions-l3-l4-rc](v10-actions-l3-l4-rc.md): the pre-rc refresh (roadmap §6 item 19). L3 against `10.0.0-alpha.4` at 1k and 100k accounts, L4's sustainable rates, cliffs, cluster and cold cache, with the code of `10.0.0-alpha.5`. Replaced when the L3 and L4 rerun before the rc merges | To be measured: a new run on Actions |

## History

The reports that left the tree, with their directories and raw results, are in git history; at commit `1357642891` they are in [`test/benchmarks/results/reports`](https://github.com/Ahoo-Wang/Wow/tree/1357642891a5a458bdb8fc7adc364af7bcb8ee21/test/benchmarks/results/reports).

## 9.x

The reports of the 9.x JMH benchmarks (`:wow-benchmarks`) are on the `v9` branch, in `wow-benchmarks/results/reports`.

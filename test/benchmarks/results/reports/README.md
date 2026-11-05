# Benchmark reports

Every report names the commit, command, environment and raw results that reproduce it. How the benchmark is run and read is in [the benchmark README](../../README.md).

Before GA the tree keeps the latest report of each kind on each environment (decision D391): two environments, the local Apple M5 Max and GitHub Actions, and two kinds, a snapshot and the comparison with Axon Framework 4. A report has a fixed name without a version, `m5max-snapshot`, `m5max-axon`, `actions-snapshot` or `actions-axon`: a `.md` and a directory of the same name. A new report replaces the one before it in the same pull request, by updating the same file; the reports under their earlier names go out then. A node's raw logs are not committed, unless they are the evidence for a conclusion.

## Current

| | Snapshot | Axon comparison |
| --- | --- | --- |
| Local, Apple M5 Max | [m5max-snapshot](m5max-snapshot.md): the saturation snapshot at `b435768e49`. Single-node ceilings on disk (PostgreSQL and MongoDB), E1 measured past 4,096 clients to its limit, three nodes on this host, and two anomalies left to explain: PostgreSQL's E1 falling past its peak, and MongoDB under contended keys | [m5max-axon](m5max-axon.md): Wow with no bus against Axon Framework 4 at `cc11b16aa8`, JDK 25, W A A W. Peaks: Wow 4.8× Axon's on E1 and 5.2–5.4× on E3 with Spring Boot's 10 connections, 2.5–2.6× and 3.3–3.4× with 32. Contention not comparable: Wow ran out of heap in one E3 Zipf session |
| GitHub Actions | `actions-snapshot`, the L3 and L4 run before the rc. Until it merges, [v10-actions-l3-l4-rc](v10-actions-l3-l4-rc.md): the pre-rc refresh (roadmap §6 item 19). L3 against `10.0.0-alpha.4` at 1k and 100k accounts, L4's sustainable rates, cliffs, cluster and cold cache, with the code of `10.0.0-alpha.5`. `actions-snapshot` replaces it | [actions-axon](actions-axon.md): Wow with no bus against Axon Framework 4 on one PostgreSQL at `3fd5349050`, both on JDK 25. E3's peak is 1.9–2.3 times Axon's, and E1 is level; with the harness's hot keys, E1 is 1.5–1.8 times Axon's. At the same rate, PostgreSQL does less work per command for Wow, and Wow allocates a quarter less per E3 command. The latencies decide nothing. One Axon error is unexplained, during an untimed prime |

## History

The reports that left the tree, with their directories and raw results, are in git history; at commit `1357642891` they are in [`test/benchmarks/results/reports`](https://github.com/Ahoo-Wang/Wow/tree/1357642891a5a458bdb8fc7adc364af7bcb8ee21/test/benchmarks/results/reports).

## 9.x

The reports of the 9.x JMH benchmarks (`:wow-benchmarks`) are on the `v9` branch, in `wow-benchmarks/results/reports`.

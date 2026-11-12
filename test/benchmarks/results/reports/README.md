# Benchmark reports

Every report names the commit, command, environment and raw results that reproduce it. How the benchmark is run and read is in [the benchmark README](../../README.md).

Before GA the tree keeps the latest report of each kind on each environment (decision D391): two environments, the local Apple M5 Max and GitHub Actions, and two kinds, a snapshot and the comparison with Axon Framework 4. A report has a fixed name without a version, `m5max-snapshot`, `m5max-axon`, `actions-snapshot` or `actions-axon`: a `.md` and a directory of the same name. A new report replaces the one before it in the same pull request, by updating the same file; the reports under their earlier names go out then. A node's raw logs are not committed, unless they are the evidence for a conclusion.

## Current

| | Snapshot | Axon comparison |
| --- | --- | --- |
| Local, Apple M5 Max | [m5max-snapshot](m5max-snapshot.md): the saturation snapshot at `d9b0781205`, JDK 25, beside the one at `a7c372ad3e`. Single-node ceilings on disk (PostgreSQL and MongoDB), E1 measured past 4,096 clients to its limit, and three nodes on this host. E3 past its knee refuses again and answers above `b435768e49` (X-118 fixed). A single node's median at 4–16 clients is about twice the earlier snapshots', unexplained. The re-measurement of MongoDB's cost per commit at a fixed 8,000/s in 12 sessions finds D420's sparse key at the tag's level: log insert time +1–3%, mongod CPU −3–5%, against +6–18% insert time with the indexes on nulls | [m5max-axon](m5max-axon.md): Wow with no bus against Axon Framework 4 at `cc11b16aa8`, JDK 25, W A A W. Peaks: Wow 4.8× Axon's on E1 and 5.2–5.4× on E3 with Spring Boot's 10 connections, 2.5–2.6× and 3.3–3.4× with 32. Contention not comparable: Wow ran out of heap in one E3 Zipf session |
| GitHub Actions | [actions-snapshot](actions-snapshot.md): L3 and L4 before the rc, at `a7c372ad3e` against `v10.0.0-beta.1`, JDK 25, with thirteen follow-up runs. E1 on MongoDB at 100k accounts has a higher p99 at an equal rate in every run against the tag, at the same CPU and store work per operation, and stays open with no cause confirmed. The access walk under the component suites is gone with #6041, and #5420 halves PostgreSQL's WAL syncs for 10% more commits. Sustainable MongoDB rates are 1.18–1.34× alpha.5's on the same CPU model; the last report's two open tails are not there again | [actions-axon](actions-axon.md): Wow with no bus against Axon Framework 4 on one PostgreSQL at `3fd5349050`, both on JDK 25. E3's peak is 1.9–2.3 times Axon's, and E1 is level; with the harness's hot keys, E1 is 1.5–1.8 times Axon's. At the same rate, PostgreSQL does less work per command for Wow, and Wow allocates a quarter less per E3 command. The latencies decide nothing. One Axon error is unexplained, during an untimed prime |

## Headline figures

The documentation's [Performance](../../../../documentation/docs/en/operate/performance.md) page quotes a snapshot's headline figures from `<report>/headline.json`, which the tool that computes them writes beside the report: `report`, `environment`, `commit`, `date`, `jdk`, and `figures`, each `{ store, nodes, scenario, keys, peak?, sustainable? }` with a figure `{ value, clients?, p99Ms? }` (`store` is `postgres` or `mongo`; `keys` is `uniform`, `zipf` or `hot`). `pnpm docs:reference` writes the page's table from them, and the documentation checks fail while the page is not what they give ([`docs-performance.mjs`](../../../../.github/scripts/docs-performance.mjs)). A report without the file is not quoted. A new report that replaces one updates its file in the same pull request, and the page with it.

## History

The reports that left the tree, with their directories and raw results, are in git history; at commit `1357642891` they are in [`test/benchmarks/results/reports`](https://github.com/Ahoo-Wang/Wow/tree/1357642891a5a458bdb8fc7adc364af7bcb8ee21/test/benchmarks/results/reports).

## 9.x

The reports of the 9.x JMH benchmarks (`:wow-benchmarks`) are on the `v9` branch, in `wow-benchmarks/results/reports`.

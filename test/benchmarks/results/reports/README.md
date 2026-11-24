# Benchmark reports

Every report names the commit, command, environment and raw results that reproduce it. How the benchmark is run and read is in [the benchmark README](../../README.md).

Before GA the tree keeps the latest report of each kind on each environment, and no older one: two environments, the local Apple M5 Max and GitHub Actions, and two kinds, a snapshot and the comparison with Axon Framework 4. A report has a fixed name without a version, `m5max-snapshot`, `m5max-axon`, `actions-snapshot` or `actions-axon`: a `.md` and a directory of the same name. A new report replaces the one before it in the same pull request, by updating the same file; the reports under their earlier names go out then. A node's raw logs are not committed, unless they are the evidence for a conclusion.

Beside them, `m5max-recovery` holds the before/after pairs of the recovery fixes, which the documentation quotes too. Its node logs are committed: they are the evidence for its times.

`m5max-idle` holds what idle nodes cost their store (#6702): one directory a session, as `bench.sh idle --report=<session>` writes it, named `<backend>-<nodes>-node[s]-<before|after>` for the before and after of a change, now the node-wide log probe (#6720). `pnpm bench:idle --table m5max-idle` prints its table. Its nodes' logs are not committed.

## Current

| | Snapshot | Axon comparison |
| --- | --- | --- |
| Local, Apple M5 Max | [m5max-snapshot](m5max-snapshot.md): the saturation snapshot at `103f9b6746` (10.0.0-beta.5), JDK 25. Single-node ceilings on disk (PostgreSQL and MongoDB), E1 measured past 4,096 clients to its limit, E3 past its knee, and three nodes on this host. No step disturbed or void | [m5max-axon](m5max-axon.md): Wow with no bus against Axon Framework 4 at `cc11b16aa8`, JDK 25, W A A W. Peaks: Wow 4.8× Axon's on E1 and 5.2–5.4× on E3 with Spring Boot's 10 connections, 2.5–2.6× and 3.3–3.4× with 32. Contention not comparable: Wow ran out of heap in one E3 Zipf session |
| GitHub Actions | [actions-snapshot](actions-snapshot.md): L3 and L4 at `v10.0.0-beta.6` (`e3a4e937aa`), JDK 25, beta.6's own figures only. Sustainable rates on one 4-core runner are 2,975/s (E1), 1,272/s (E2) and 1,008/s (E3) on MongoDB on a Xeon 6975P-C, and 1,958/s (E1) and 840/s (E3) on PostgreSQL on an EPYC 7763. E3 keeps up to 500/s on both stores. Three nodes carry the full rate steady. In MongoDB's E3 hand-over, 39 of 4,500 waits time out after node 2's restart, the shape of #6407, whose fix (#6412) is not in this build. At 1k accounts on MongoDB, L3's rounds ran above what the node held, from this run's calibration | [actions-axon](actions-axon.md): Wow with no bus against Axon Framework 4 on one PostgreSQL at `3fd5349050`, both on JDK 25. E3's peak is 1.9–2.3 times Axon's, and E1 is level; with the harness's hot keys, E1 is 1.5–1.8 times Axon's. At the same rate, PostgreSQL does less work per command for Wow, and Wow allocates a quarter less per E3 command. The latencies decide nothing. One Axon error is unexplained, during an untimed prime |
| Recovery | [m5max-recovery](m5max-recovery.md): a node losing all 64 leases at once (#6379), a planned hand-over (#6407, with #6412 and with #6424), the migration rehearsal's catch-up under live orders with two permits and with no catch-up bound (`wow.subscription.max-catch-ups`), and a node's first activation on a migrated history (#6412, then #6424, each against its parent). Each pair is a build against the one before it, JDK 25, the first two on the M5 Max and the rehearsal on Actions. With the fixes, all 64 partitions were back within 3.5 s, against none before 47 s; the hand-over's timed-out waits went from 15, 2 and 2 to 1, 1 and 2; with two permits no live order failed, against 7 and 2,587 with no bound | |
| Idle | [m5max-idle](m5max-idle/): before and after the node-wide log probe (#6720), at `6e33125923` and at its merge `ddb60440a8`, JDK 25, MongoDB and PostgreSQL at one node and at three, idle with nothing sent to them. Before, about 64 log head reads a second for the context's 64 partitions, three nodes as many as one. After, 2.3 log counter reads a second a node, two probes of the counters and about 0.3 head reads (the backstop, the sampler and the health check), and 6.8–6.9 for a service of three, as GitHub Actions counts them on the same code. The store's CPU added by the idle nodes, in percent of one core, before and after: MongoDB 3.1 and 1.0 (one node), 5.6 and 2.6 (three); PostgreSQL 0.5 and 0.1, 1.5 and 0.8 | |

## Headline figures

The documentation's [Performance](../../../../documentation/docs/en/operate/performance.md) page quotes the reports' figures from `<report>/headline.json` (schema `wow-headline/1`), which [`bench-headline.mjs`](../../../../.github/scripts/bench-headline.mjs) writes from the report's own data, so that no figure reaches a page by hand:

- **Actions reports:** each suite's `suite.json`, through `bench.mjs`'s own views:
  - a capacity suite's sustainable rates;
  - the cliff, cold-cache and cluster runs;
  - every L3 round of the head as a unit cost at its rate, the node's, the store's and Kafka's CPU apart;
  - a comparison's Wow-over-Axon ranges with their A/A spread and verdict, and each session's storage and startup.
- **The M5 Max snapshot:** its `summary.json`, which `scripts/summarize.py --json` writes from the same values as its `SUMMARY.md`.
- **The recovery report:** its `summary.json`, which its own `scripts/summarize.py --json` writes from the raw inputs beside it; each figure is one arm, with its own build, machine and JDK.

Each figure carries its build (`commit`), the runner's CPU model (`cpu`), its run when the report names one (`run`), and the file it comes from (`source`). None is computed across two reports, so none across two builds.

A page quotes them two ways, and any page of the documentation may:

- **A named block**, a table between `<!-- performance:begin <name> -->` and `<!-- performance:end -->`. The Performance page holds `snapshots`, each quoted report's rates; `BLOCKS` in `docs-performance.mjs` names the others.
- **An inline span**, one figure's number in the prose: `<!-- perf:<report>/<figure id>:<field> --><!-- /perf -->`, the field a dotted path for a nested one (`sustainable.value`). A rate prints as `2,609.8/s`, a time (`…Ms`) as `160.8 ms`, and a peak that is a lower bound with `≥`. A span whose report, figure or number the headlines do not hold fails the check.

`pnpm docs:reference` writes the headlines, the pages' blocks and their spans together. The documentation checks fail while either is not what the data gives ([`docs-performance.mjs`](../../../../.github/scripts/docs-performance.mjs)). A report that replaces another changes its data, and its headline and the pages with it, in the same pull request.

## Reading a comparison

A paired run calls a latency ratio beyond noise when its 95% interval clears the suite's A/A threshold (`test/benchmarks/noise.json`, written from `noise-history.json`; the [benchmark guide](../../README.md) gives the rule). One L3 run can flag only a large latency change. With these thresholds and the width of one run's own intervals, the smallest change it can call beyond noise is a median of about 83% at p50 (48% to 135% by suite) and about 51% at p99 (34% to 95%), from the history as first built. A change of 10 to 20% needs an alternating run with more rounds (`repeats`), whose intervals are narrower. CPU and allocation per operation are reported as ratios with no threshold: nothing judges them beyond noise.

## History

The reports that left the tree, with their directories and raw results, are in git history; at commit `1357642891` they are in [`test/benchmarks/results/reports`](https://github.com/Ahoo-Wang/Wow/tree/1357642891a5a458bdb8fc7adc364af7bcb8ee21/test/benchmarks/results/reports).

## 9.x

The reports of the 9.x JMH benchmarks (`:wow-benchmarks`) are on the `v9` branch, in `wow-benchmarks/results/reports`.

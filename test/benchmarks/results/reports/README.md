# Benchmark reports

Every report names the commit, command, environment and raw results that reproduce it. How the benchmark is run and read is in [the benchmark README](../../README.md).

## Wow 10

[v10-vs-9x](v10-vs-9x.md) is the current end-to-end report (roadmap B.R, 9.2), written from the L3 and L4 runs of the benchmark workflow (`bench.yml`) on 2026-10-22. It compares Wow 10 with 9.x, checks the current code against `10.0.0-alpha.4`, and gives the sustainable rates, the cliff, a three-node cluster with a hand-over, a cold cache over a million accounts, and the store round trips and allocation per operation.

Earlier Wow 10 evidence, each valid for the commit it names:

| Report | What it answers | Status |
| --- | --- | --- |
| [v10-h1-postgres-partition-sequence](v10-h1-postgres-partition-sequence.md), [v10-h1-mongo-partition-sequence](v10-h1-mongo-partition-sequence.md) | Spike H1: partition sequences under a row lock and in MongoDB transactions | Spike, decided (D10, D11, D28) |
| [v10-h2-routing](v10-h2-routing.md) | Spike H2: whether affinity routing pays for its forwarding hop | Spike, decided (D6) |
| [v10-h3-kernel-comparison](v10-h3-kernel-comparison.md) | Spike H3: the coroutine kernel against the 9.3 Reactor kernel | Spike, decided |
| [v10-h4-ksp-coverage](v10-h4-ksp-coverage.md) | Spike H4: KSP binds the annotation model | Spike, decided |
| [v10-h6-leases](v10-h6-leases.md) | Spike H6: storage leases for membership and rebalancing | Spike, decided |
| [v10-mongo-write-amplification](v10-mongo-write-amplification.md) | B.D: store operations per command on MongoDB | Diagnostic; the paths changed since (D248, D261) |
| [v10-counts-e1-e3](v10-counts-e1-e3.md) | Store round trips, operations and allocations per operation, 9.x against Wow 10 | Superseded by [v10-vs-9x](v10-vs-9x.md#store-round-trips-and-allocation-per-operation-l1); measured before R3-A, R3-B, P.16, D248 and D261 |
| [v10-ps2-partition-idle](v10-ps2-partition-idle.md) | PS-2: what an idle partition costs | Valid for its commit |
| [v10-vs-9x-smoke](v10-vs-9x-smoke.md) | The harness's first end-to-end smoke run | Not a benchmark |

## 9.x

[`9.x/`](9.x/) holds the reports of the 9.x JMH benchmarks (`:wow-benchmarks`), with the accepted 9.x baseline `framework-e2e-baseline.json`. They measure the 9.x command pipeline and stores, not Wow 10, and the Gradle tasks that wrote them left with the 9.x code (decision D262): they cannot be regenerated on `main`, and their paths (`wow-benchmarks/...`) are those of the 9.x tree, which is on the `v9` branch. They are kept as history (decision D265 ⑤).

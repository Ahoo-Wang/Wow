# Wow Benchmarks

This module contains Wow's JMH benchmark suites and report-generation tasks.
Use it for three jobs:

- keeping benchmark entry points healthy in PRs
- collecting quick local regression feedback
- producing fuller reports when a change needs performance evidence

## Benchmark Layers

| Layer | Scope | Use For | Main Tasks |
|-------|-------|---------|------------|
| Smoke | A small cross-section of component and Framework E2E benchmarks. | PR safety; proves the JMH jar and selected benchmark paths still run. | `benchmarkSmoke` |
| Framework E2E | Command write path with in-memory or noop infrastructure. | Framework throughput and latency feedback without Redis or Mongo noise. Full E2E is the formal framework conclusion source. | `benchmarkQuickE2E`, `benchmarkFullE2E` |
| Component | Isolated command, aggregate, event, wait, serialization, accessor, and pipeline pieces. | Explaining bottlenecks seen in E2E results. Do not publish component scores as standalone framework capacity. | `benchmarkQuickComponent`, `benchmarkFullComponent` |
| Infrastructure E2E | Command write path through Redis or Mongo persistence. | Storage-path bottleneck checks when local services are available. | `benchmarkQuickInfrastructureE2E`, `benchmarkFullInfrastructureE2E` |

## Recommended Workflows

### PR Safety

```bash
./gradlew :wow-benchmarks:benchmarkSmoke
```

Smoke is intentionally short and does not produce a performance report.

### Default Local Report

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E :wow-benchmarks:generateBenchmarkReport
```

This writes the checked-in quick Framework E2E report to [`reports/quick-framework-e2e.md`](reports/quick-framework-e2e.md).
Treat it as directional local feedback; use Full E2E before making formal performance claims.

### Quick Bottleneck Diagnosis

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E
./gradlew :wow-benchmarks:benchmarkQuickComponent
./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E
./gradlew :wow-benchmarks:generateQuickBenchmarkReport
```

The quick grouped report is written to `wow-benchmarks/reports/quick-grouped.md`.

### Full Baseline Run

```bash
./gradlew :wow-benchmarks:benchmarkFullE2E --no-parallel
./gradlew :wow-benchmarks:benchmarkFullComponent --no-parallel
./gradlew :wow-benchmarks:benchmarkFullInfrastructureE2E --no-parallel
./gradlew :wow-benchmarks:generateGroupedBenchmarkReport
```

Full runs are expensive. Prefer `--no-parallel` for stable measurements, especially on developer machines where parallel JMH tasks compete for CPU and memory.

The full grouped report is written to `wow-benchmarks/reports/full-grouped.md`.

## Reports And Results

| Path | Contents | Commit Policy |
|------|----------|---------------|
| `wow-benchmarks/reports/quick-framework-e2e.md` | Generated quick Framework E2E report. | Commit when intentionally updating the visible benchmark report. |
| `wow-benchmarks/reports/quick-grouped.md` | Generated quick E2E/component/infrastructure grouped report. | Commit when intentionally updating grouped benchmark evidence. |
| `wow-benchmarks/reports/full-grouped.md` | Generated full E2E/component/infrastructure grouped report. | Commit when intentionally updating formal benchmark evidence. |
| `wow-benchmarks/results/jmh/` | Local JMH JSON and human-readable outputs. | Do not commit generated run output. |
| `wow-benchmarks/results/framework-e2e-baseline.json` | Framework E2E comparison baseline, when present. | Commit only intentional baseline updates. |

Files under `reports/*.md` are generated. Do not hand-edit benchmark rows; rerun the benchmark/report task instead.

## Reading The Report

- `thrpt` scores are throughput, normally shown as `ops/s`. Higher is better.
- `avgt` scores are average latency. The report converts tiny JMH `s/op` values to readable latency units such as `us/op`.
- `gc.alloc.rate.norm` is normalized allocation per operation. Lower is usually better.
- Quick reports are useful for local regression checks, but the shorter run profile has wider variance than Full E2E.
- Framework E2E reports isolate Wow command-pipeline overhead; they are not Redis, Mongo, or production deployment capacity numbers.

## Configuration

Default quick threads are `1,4`:

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E -PbenchmarkQuickThreads=1,4
```

Default full threads are `1,2,4,8`:

```bash
./gradlew :wow-benchmarks:benchmarkFullE2E -PbenchmarkThreads=1,2,4,8 --no-parallel
```

Benchmark JVM args are defined in `wow-benchmarks/gradle/benchmarking.gradle.kts` and are included in generated reports.

## Infrastructure Requirements

Infrastructure E2E benchmarks require local services:

| Service | Endpoint |
|---------|----------|
| Redis | `localhost:6379` |
| MongoDB | `localhost:27017` |

If these services are not running, use Framework E2E and Component benchmarks instead.

## Baseline Utilities

```bash
./gradlew :wow-benchmarks:benchmarkCompare
./gradlew :wow-benchmarks:updateBenchmarkBaseline
```

Use `benchmarkCompare` after collecting the relevant Framework E2E results. Use `updateBenchmarkBaseline` only when the current benchmark output is accepted as the new baseline.

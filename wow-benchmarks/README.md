# Wow Benchmarks

This module contains the JMH benchmark suites for Wow. Use it to validate benchmark entry health, collect local regression feedback, and generate measured performance reports.

## Benchmark Layers

- **Smoke**: PR safety check. It verifies selected JMH paths still compile and execute, but it is not a performance report.
- **Framework E2E**: command pipeline overhead with in-memory or noop stores. Full E2E results are the source for framework performance conclusions.
- **Component**: isolated runtime pieces used to explain bottlenecks. Do not report component scores as standalone framework performance goals.
- **Infrastructure E2E**: Redis and Mongo persistence paths when local services are available.

## Commands

```bash
./gradlew :wow-benchmarks:benchmarkSmoke
```

Generate the default quick Framework E2E report:

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E :wow-benchmarks:generateBenchmarkReport
```

The generated quick report is written to [`REPORT.md`](REPORT.md).

Quick grouped feedback:

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E
./gradlew :wow-benchmarks:benchmarkQuickComponent
./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E
./gradlew :wow-benchmarks:generateQuickBenchmarkReport
```

Full benchmark run:

```bash
./gradlew :wow-benchmarks:benchmarkFullE2E
./gradlew :wow-benchmarks:benchmarkFullComponent
./gradlew :wow-benchmarks:benchmarkFullInfrastructureE2E
./gradlew :wow-benchmarks:generateGroupedBenchmarkReport
```

Full runs are the formal performance baseline path. They are intentionally expensive; prefer `--no-parallel` for stable measurements:

```bash
./gradlew :wow-benchmarks:benchmarkFullE2E --no-parallel
```

Grouped reports are written under `wow-benchmarks/build/reports/jmh/`.

## Result Files

JMH JSON output is written under `wow-benchmarks/results/`. Full E2E result JSON files are local artifacts and are not checked in by default.

Infrastructure benchmarks require local Redis and MongoDB services.

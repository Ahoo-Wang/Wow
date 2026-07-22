# Wow Benchmarks

This module contains Wow's JMH benchmark suites and report-generation tasks.
Use it for three jobs:

- keeping benchmark entry points healthy in PRs
- collecting quick local regression feedback
- producing fuller reports when a change needs performance evidence

## Benchmark Layers

| Layer | Scope | Use For | Main Tasks |
|-------|-------|---------|------------|
| Smoke | A small cross-section of component, Framework E2E, and WebFlux adapter benchmarks. | PR safety; proves the JMH jar and selected benchmark paths still run. | `benchmarkSmoke` |
| Framework E2E | Synchronous command send/write round trips with in-memory or noop infrastructure. | Quick feedback, exact-workload regression baselines, and optional latency diagnosis; not production capacity. | `benchmarkQuickE2E`, `benchmarkBaselineE2E`, `benchmarkLatencyE2E` |
| Batch CommandWrite E2E | Paired 32-command workloads using either 32 blocking boundaries or one sequential/concurrent batch boundary. | Primary framework-cost signal without per-command blocking distortion, plus bounded-concurrency scaling diagnosis. | `benchmarkQuickBatchE2E` |
| Component | Isolated command, aggregate, event, wait, serialization, accessor, and pipeline pieces. | Quick feedback, targeted diagnosis, or rare exhaustive catalog checks. | `benchmarkQuickComponent`, `benchmarkDiagnosticComponent`, `benchmarkExhaustiveComponent` |
| WebFlux Adapter | Spring WebFlux request, response, SSE, and aggregate tracing adapter paths without a real Netty server. | Diagnosing HTTP adapter overhead and WebFlux-specific allocation hot spots. These results are not Framework E2E conclusion data. | `benchmarkQuickWebFlux`, `benchmarkExhaustiveWebFlux` |
| Infrastructure E2E | Command write path through Redis or Mongo persistence. | Storage-path bottleneck checks when local services are available. | `benchmarkQuickInfrastructureE2E`, `benchmarkBaselineInfrastructureE2E` |
| Async Profiling | Short, selected CPU profiles written outside comparable quick/baseline/exhaustive results. | Producing flamegraphs after a regression has been isolated. | `benchmarkAsyncE2E`, `benchmarkAsyncComponent`, `benchmarkAsyncWebFlux` |

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

This writes the checked-in quick Framework E2E report to [`results/reports/quick-framework-e2e.md`](results/reports/quick-framework-e2e.md).
Quick uses throughput mode, a `1x2s` warmup, `2x3s` measurements, one fork, the GC profiler, and a 1 GiB heap. Treat it as directional local feedback; use Baseline E2E before making formal performance claims.

### Batch CommandWrite E2E Report

```bash
./gradlew :wow-benchmarks:benchmarkQuickBatchE2E :wow-benchmarks:generateBatchBenchmarkReport
```

This writes the checked-in Batch CommandWrite report to [`results/reports/quick-batch-command-write-e2e.md`](results/reports/quick-batch-command-write-e2e.md).
Each JMH invocation sends 32 independent commands through the complete command-write path. The paired workloads compare 32 individual `block()` calls with one reactive batch using concurrency `1` or `4`. `@OperationsPerInvocation(32)` normalizes throughput and allocation to one command, not one batch. The suite uses one JMH thread and remains separate from Framework Quick and Baseline so it does not lengthen their iteration loop.
Read the paired signals by role: Sequential c1 is the primary framework-cost signal because it amortizes the harness boundary without adding command concurrency; Concurrent c4 is the scaling signal; Individual blocks is the control that quantifies per-command blocking distortion. These roles do not turn the short Quick profile into a formal regression source.
The nine-workload matrix has a theoretical measurement floor of 72 seconds; the reference validation run completed in `1m19s`.

### Infrastructure E2E Report

```bash
./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E :wow-benchmarks:generateInfrastructureBenchmarkReport
```

This generates `results/reports/quick-infrastructure-e2e.md` on demand. The repository does not retain stale Infrastructure evidence when a current Redis/Mongo run is unavailable.
Infrastructure E2E requires local Redis and MongoDB services and measures persistence-path behavior, not framework-only overhead. Commit the generated report only with intentionally collected, provenance-backed Infrastructure results.

### WebFlux Adapter Report

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickWebFluxThreads=1
./gradlew :wow-benchmarks:generateQuickBenchmarkReport \
  -PbenchmarkQuickThreads=1 \
  -PbenchmarkQuickWebFluxThreads=1
```

WebFlux Adapter results are included in the grouped report when the suite has
been run. They measure Spring WebFlux adapter code such as request handling,
response conversion, SSE wrapping, and aggregate tracing replay. They do not
start a real Netty server and are not Framework E2E performance conclusions.
The grouped report still requires Framework E2E results, so the example keeps
both tasks on the same one-thread quick profile.

### Quick Bottleneck Diagnosis

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E
./gradlew :wow-benchmarks:benchmarkQuickComponent
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickWebFluxThreads=1
./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E
./gradlew :wow-benchmarks:generateQuickBenchmarkReport -PbenchmarkQuickWebFluxThreads=1
```

The quick grouped report is written to `wow-benchmarks/results/reports/quick-grouped.md`.
Quick Component intentionally runs a representative 27-case matrix per thread instead of the complete 60-case matrix.
The clean Framework E2E + Component + WebFlux quick evidence bundle completed in `7m34s` on the reference development machine.

### Bounded Framework Baseline

```bash
./gradlew :wow-benchmarks:benchmarkBaselineE2E --no-parallel
./gradlew :wow-benchmarks:benchmarkCompare
```

Baseline E2E is the formal regression source for its exact synchronous command-send and command-write workloads; it is not a production capacity model. Its single-command blocking rows remain regression controls, while Batch Sequential c1 is the primary CommandWrite framework-cost signal. Baseline keeps `threads=1,4` and two independent forks, with bounded `2x3s` warmup and `3x5s` measurement iterations. The eight-workload matrix has a theoretical measurement floor of about 11 minutes; the clean reference run completed in `11m44s`. Use `updateBenchmarkBaseline` only after reviewing the comparison in a controlled environment.

Average-time measurement is optional and isolated so it does not delay every baseline run:

```bash
./gradlew :wow-benchmarks:benchmarkLatencyE2E --no-parallel
```

The default single-thread latency task has a theoretical measurement floor of about three minutes.

### Component Diagnosis

```bash
./gradlew :wow-benchmarks:benchmarkDiagnosticComponent \
  -PbenchmarkDiagnosticComponentIncludes=me.ahoo.wow.benchmark.component.CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent
```

Diagnostic Component defaults to throughput, one thread, one fork, and the representative quick catalog. Select exact methods with `benchmarkDiagnosticComponentIncludes`; request average time with `-PbenchmarkDiagnosticModes=avgt` or both modes with `thrpt,avgt`.

The complete 60-workload catalog remains available as an explicit escape hatch and is no longer part of the normal iteration loop:

```bash
./gradlew :wow-benchmarks:benchmarkExhaustiveComponent --no-parallel
```

Its default throughput-only single-thread profile has a theoretical measurement floor of about eight minutes. Generic aliases are intentionally absent; select a purpose-specific task.

### Optional Exhaustive Grouped Report

```bash
./gradlew :wow-benchmarks:benchmarkBaselineE2E --no-parallel
./gradlew :wow-benchmarks:benchmarkExhaustiveComponent --no-parallel
./gradlew :wow-benchmarks:benchmarkExhaustiveWebFlux --no-parallel
./gradlew :wow-benchmarks:benchmarkBaselineInfrastructureE2E --no-parallel
./gradlew :wow-benchmarks:generateBaselineBenchmarkReport
```

Run this only when a cross-layer evidence package is required. Prefer `--no-parallel` for stable measurements because parallel JMH tasks compete for CPU and memory.

The formal grouped report is written to `wow-benchmarks/results/reports/baseline-grouped.md`.

### On-Demand CPU Profiling

```bash
./gradlew :wow-benchmarks:benchmarkAsyncE2E \
  -PbenchmarkAsyncE2EIncludes=me.ahoo.wow.benchmark.e2e.CommandWriteE2EBenchmark.sendAndWaitProcessed

./gradlew :wow-benchmarks:benchmarkAsyncComponent \
  -PbenchmarkAsyncComponentIncludes=me.ahoo.wow.benchmark.component.CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent
```

Async tasks use a short single-thread throughput profile and write flamegraphs under `wow-benchmarks/build/profiling/async/<suite>/threads-<n>/`. They are diagnostic only and are excluded from reports, comparisons, and baselines. The task fails if AsyncProfiler is unavailable; configure a custom library with `-PbenchmarkAsyncProfilerLib=/path/to/libasyncProfiler.dylib`.

## Task Model

The Gradle model keeps four responsibilities separate: `BenchmarkSuite` owns workload selection and required services; `BenchmarkRunProfile` owns JMH methodology and result namespace; `BenchmarkTaskSpec` explicitly binds a task name to one suite and profile; reports consume those task specs as their source of truth. Do not derive task names from suite/profile IDs or add historical aliases to the core model.

## Reports And Results

| Path | Contents | Commit Policy |
|------|----------|---------------|
| `wow-benchmarks/results/reports/quick-framework-e2e.md` | Generated quick Framework E2E report. | Commit when intentionally updating the visible benchmark report. |
| `wow-benchmarks/results/reports/quick-batch-command-write-e2e.md` | Generated quick Batch CommandWrite E2E report. | Commit when intentionally updating the visible batch benchmark report. |
| `wow-benchmarks/results/reports/quick-infrastructure-e2e.md` | Quick Infrastructure E2E report generated on demand; it may be absent in a fresh checkout. | Commit only with intentionally collected, provenance-backed Redis/Mongo evidence. |
| `wow-benchmarks/results/reports/quick-grouped.md` | Generated quick E2E/component/infrastructure grouped report. | Commit when intentionally updating grouped benchmark evidence. |
| `wow-benchmarks/results/reports/baseline-grouped.md` | Generated Baseline E2E/exhaustive Component/infrastructure grouped report. | Commit when intentionally updating formal benchmark evidence. |
| `wow-benchmarks/results/jmh/` | Local JMH JSON and human-readable outputs. | Do not commit generated run output. |
| `wow-benchmarks/results/baselines/framework-e2e.json` | Framework E2E comparison baseline, when present. | Commit only intentional baseline updates. |

Files under `results/reports/*.md` are generated. Do not hand-edit benchmark rows; rerun the benchmark/report task instead.
Every successful thread-level JMH run writes a neighboring `*.manifest.json` sidecar with the source commit and dirty state, run specification, resolved profiler arguments, runtime, and SHA-256 digests for the JSON and human output. Failed runs do not publish a success manifest. Report and comparison tasks reject raw results with missing, mixed, or mismatched manifests.

## Reading The Report

- `thrpt` scores are throughput. Reports use decimal prefixes (`k`, `M`, `G`) so, for example, `1.57 k ops/s` means 1,570 operations per second. Higher is better.
- `avgt` scores are average latency. Reports automatically select `ns/op`, `µs/op`, `ms/op`, or `s/op`.
- `gc.alloc.rate.norm` is normalized allocation per operation. Reports use binary prefixes (`KiB`, `MiB`, `GiB`); lower is usually better.
- `±` is the JMH-reported error. Compact units affect presentation only; reports retain raw precision for sorting, comparisons, and regression gates.
- Quick reports contain throughput and allocation results only. They are useful for local regression checks, but the shorter run profile has wider variance than Baseline E2E.
- `benchmarkCompare` displays baseline/current JMH errors when available. The current regression gate still uses the configured point-estimate threshold; the errors are diagnostic and do not yet change the status calculation.
- Framework E2E reports isolate Wow command-pipeline overhead; they are not Redis, Mongo, or production deployment capacity numbers.
- WebFlux Adapter reports isolate functional WebFlux adapter code. They are useful for adapter bottleneck diagnosis, but they are not HTTP server capacity or Framework E2E conclusion numbers.

## Configuration

Default Framework E2E quick threads are `1,4`:

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E -PbenchmarkQuickThreads=1,4
```

Quick Component defaults to one thread because component isolation, not scaling, is its responsibility:

```bash
./gradlew :wow-benchmarks:benchmarkQuickComponent -PbenchmarkQuickComponentThreads=1
```

Quick WebFlux uses its own thread property because it has a shorter run profile:

```bash
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickWebFluxThreads=1,4
```

Baseline, latency, diagnostic, and exhaustive profiles use purpose-specific properties:

```bash
./gradlew :wow-benchmarks:benchmarkBaselineE2E -PbenchmarkBaselineThreads=1,4 --no-parallel
./gradlew :wow-benchmarks:benchmarkLatencyE2E -PbenchmarkLatencyThreads=1 --no-parallel
./gradlew :wow-benchmarks:benchmarkDiagnosticComponent \
  -PbenchmarkDiagnosticThreads=1 -PbenchmarkDiagnosticModes=thrpt
./gradlew :wow-benchmarks:benchmarkExhaustiveComponent \
  -PbenchmarkExhaustiveThreads=1 -PbenchmarkExhaustiveModes=thrpt
./gradlew :wow-benchmarks:benchmarkExhaustiveWebFlux \
  -PbenchmarkExhaustiveWebFluxThreads=1,4 --no-parallel
./gradlew :wow-benchmarks:benchmarkBaselineInfrastructureE2E \
  -PbenchmarkBaselineInfrastructureThreads=1,4 --no-parallel
```

Async tasks default to one thread. `benchmarkAsyncE2EIncludes`, `benchmarkAsyncComponentIncludes`, and `benchmarkAsyncWebFluxIncludes` accept comma-separated exact benchmark class or method names.

Quick, Diagnostic, and Exhaustive Component use a 1 GiB heap without `AlwaysPreTouch`; Baseline and Latency E2E use the stable 4 GiB benchmark JVM configuration; Async uses a 2 GiB profiling JVM. All resolved JVM and profiler arguments are included in the run manifest.

## Infrastructure Requirements

Infrastructure E2E benchmarks require local services:

| Service | Endpoint |
|---------|----------|
| Redis | `localhost:6379` |
| MongoDB | `localhost:27017` |

For Redis, use the benchmark Docker profile:

```bash
docker compose \
  --env-file wow-benchmarks/docker/benchmark.env \
  -f wow-benchmarks/docker/compose.redis.yml up -d
```

The Redis profile defaults to `redis:7.4.9-alpine`, uses tmpfs-backed data, disables
RDB/AOF persistence, and sets `io-threads=2` with threaded reads. This keeps
local Docker CPU contention lower during the `threads=1,4` infrastructure runs.

For MongoDB, use the benchmark Docker profile:

```bash
docker compose \
  --env-file wow-benchmarks/docker/benchmark.env \
  -f wow-benchmarks/docker/compose.mongo.yml up -d
```

The MongoDB profile defaults to `mongo:8.3.4`, uses tmpfs-backed data, keeps the
WiredTiger cache at 2 GiB, disables diagnostic and TTL background work, and
disables WiredTiger collection and journal compression to reduce local CPU
overhead in write-heavy infrastructure benchmarks.

Docker image tags, container names, host ports, tmpfs sizes, Mongo credentials,
healthcheck intervals, Docker log retention, and the Mongo WiredTiger cache size
are configured in `wow-benchmarks/docker/benchmark.env`. The benchmark Gradle
tasks read the same file and pass those values to the JMH process. Use
`-PbenchmarkDockerEnvFile=/path/to/env-file` when running benchmarks with a
custom Compose env file. The default Compose profiles bind published ports to
Docker's default host interface, use Docker's `local` log driver, and keep
healthchecks frequent during startup but less frequent during steady-state
benchmark runs to reduce measurement noise.

If these services are not running, use Framework E2E and Component benchmarks instead.

## Baseline Utilities

```bash
./gradlew :wow-benchmarks:benchmarkCompare
./gradlew :wow-benchmarks:updateBenchmarkBaseline
```

Use `benchmarkCompare` after collecting the relevant Framework E2E results. Use `updateBenchmarkBaseline` only when the current benchmark output is accepted as the new baseline. Baseline publication requires both the current workspace and every source manifest to be clean, and the manifest commit must equal `HEAD`.
Benchmark class names, method names, modes, threads, and parameters are part of the comparison identity. After changing any of them, collect a new Baseline E2E run in the same controlled environment before intentionally replacing the baseline; do not promote Quick, Latency, Diagnostic, Async, or old JSON under the new identity.
Schema v2 stores the source commit, run ID, JMH jar hash, exact run specification, JVM/OS runtime, per-thread result hashes, and result rows. Comparison rejects baselines whose schema, identity, run specification, clean-source state, or provenance is incomplete.

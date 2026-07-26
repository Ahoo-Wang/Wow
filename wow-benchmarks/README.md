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
| Mongo Batch Append | A 128-event-stream append-path workload comparing EventStore `insertOne`, native unordered `insertMany`, and coordinated batching. | Separating protocol Bulk capability from the end-to-end coordinated path with real local MongoDB I/O. | `benchmarkQuickMongoBatchAppend`, `benchmarkConfirmMongoBatchAppend`, `benchmarkMongoBatchAppendPairedE2E` |
| Elasticsearch Batch Append | A 128-event-stream append-path workload comparing EventStore `create`, native Bulk `create`, and coordinated Bulk `create` with `refresh=false,true`. | Measuring Elasticsearch Bulk capability and the end-to-end coordinated path without changing no-overwrite semantics. | `benchmarkQuickElasticsearchBatchAppend`, `benchmarkConfirmElasticsearchBatchAppend` |
| Storage Batch Options Tuning | Coordinated EventStore writes at isolated (1), burst (32), representative (128), and saturated (512) append counts across encoded `maxSize/maxDelay` candidates. | Screening and confirming storage-specific defaults without repeating unrelated direct/native controls for every candidate. | `benchmarkTuneMongoBatchOptions`, `benchmarkTuneElasticsearchBatchOptions`, `benchmarkConfirmMongoBatchOptions`, `benchmarkConfirmElasticsearchBatchOptions` |
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

`benchmarkCompare` reports threshold crossings as regression or improvement candidates instead of treating one run as a confirmed cross-run change. Coverage changes still fail comparison. Confirm a candidate with the same JVM, fork, warmup, measurement, and GC-profiler configuration as Baseline while selecting only the affected method and parameters:

```bash
./gradlew :wow-benchmarks:benchmarkConfirmE2E \
  -PbenchmarkConfirmE2EThreads=4 \
  -PbenchmarkConfirmE2EIncludes=me.ahoo.wow.benchmark.e2e.CommandSendE2EBenchmark.sendAndWaitSent \
  '-PbenchmarkConfirmE2EParameters=gatewayScenario=validated' --no-parallel

./gradlew :wow-benchmarks:benchmarkConfirmE2E \
  -PbenchmarkConfirmE2EThreads=4 \
  -PbenchmarkConfirmE2EIncludes=me.ahoo.wow.benchmark.e2e.CommandWriteE2EBenchmark.sendAndWaitProcessed \
  '-PbenchmarkConfirmE2EParameters=scenario=ceiling;schedulerStrategy=IMMEDIATE,PARALLEL' --no-parallel
```

Confirmation results are diagnostic evidence under `results/jmh/confirmation/`; they never replace the accepted baseline or enter grouped reports automatically.

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
| `wow-benchmarks/results/reports/quick-mongo-batch-append.md` | Generated quick Mongo EventStore append comparison. | Commit with intentionally collected, provenance-backed MongoDB evidence. |
| `wow-benchmarks/results/reports/confirmation-mongo-batch-append.md` | Generated multiple-fork Mongo EventStore append comparison. | Commit intentionally collected provenance-backed evidence; use for independent JMH point estimates and error intervals. |
| `wow-benchmarks/results/reports/mongo-batch-append-paired-e2e.md` | Generated AB/BA paired Mongo EventStore append E2E report. | Commit intentionally collected provenance-backed evidence; prefer for the quantified local batching-gain conclusion. |
| `wow-benchmarks/results/reports/quick-elasticsearch-batch-append.md` | Generated quick Elasticsearch EventStore append comparison. | Commit with intentionally collected, provenance-backed Elasticsearch evidence. |
| `wow-benchmarks/results/reports/confirmation-elasticsearch-batch-append.md` | Generated multiple-fork Elasticsearch EventStore append comparison. | Commit intentionally collected provenance-backed evidence; use for independent JMH point estimates and error intervals. |
| `wow-benchmarks/results/reports/tuning-mongo-batch-options.md` | Generated Mongo EventStore batch-options screening matrix. | Commit only with the exact candidate grid and clean-source manifest used to choose confirmation candidates. |
| `wow-benchmarks/results/reports/tuning-elasticsearch-batch-options.md` | Generated Elasticsearch EventStore batch-options screening matrix. | Commit only with the exact candidate grid and clean-source manifest used to choose confirmation candidates. |
| `wow-benchmarks/results/reports/confirmation-mongo-batch-options.md` | Generated multiple-fork Mongo batch-options confirmation. | Commit with the current default and selected candidate set before changing defaults. |
| `wow-benchmarks/results/reports/confirmation-elasticsearch-batch-options.md` | Generated multiple-fork Elasticsearch batch-options confirmation. | Commit with the current default and selected candidate set before changing defaults. |
| `wow-benchmarks/results/reports/quick-infrastructure-e2e.md` | Quick Infrastructure E2E report generated on demand; it may be absent in a fresh checkout. | Commit only with intentionally collected, provenance-backed Redis/Mongo evidence. |
| `wow-benchmarks/results/reports/quick-grouped.md` | Generated quick E2E/component/infrastructure grouped report. | Commit when intentionally updating grouped benchmark evidence. |
| `wow-benchmarks/results/reports/baseline-grouped.md` | Generated Baseline E2E/exhaustive Component/infrastructure grouped report. | Commit when intentionally updating formal benchmark evidence. |
| `wow-benchmarks/results/jmh/` | Local JMH JSON and human-readable outputs. | Do not commit generated run output. |
| `wow-benchmarks/results/baselines/framework-e2e.json` | Framework E2E comparison baseline, when present. | Commit only intentional baseline updates. |

Files under `results/reports/*.md` are generated. Do not hand-edit benchmark rows; rerun the benchmark/report task instead.
Every successful thread-level JMH run writes a neighboring `*.manifest.json` sidecar with the source commit and dirty state, run specification, resolved required-service endpoints, profiler arguments, runtime, and SHA-256 digests for the JSON and human output. Failed runs do not publish a success manifest. Report and comparison tasks reject raw results with missing, mixed, or mismatched manifests.
Infrastructure reports validate captured service names against the suite identity and display the captured host/port provenance. They do not reinterpret historical evidence using report-time Redis, MongoDB, or Elasticsearch environment variables.

### Mongo Batch Append Reports

```bash
./gradlew :wow-benchmarks:benchmarkQuickMongoBatchAppend \
  :wow-benchmarks:generateMongoBatchAppendBenchmarkReport \
  --no-parallel
```

Each workload writes 128 independent event streams and normalizes JMH throughput and average time per event.
The three paths are EventStore `insertOne`, native unordered `insertMany`, and `MongoEventStore` coordinated
batching with `maxSize=128` and `maxDelay=1ms`. This separates the driver's native batch capability from
the end-to-end coordinated path; their delta also includes batch formation and possible partial flushes.
The task requires only MongoDB. Quick results are directional point estimates.

For quantified local evidence with multiple forks and JMH error intervals:

```bash
./gradlew :wow-benchmarks:benchmarkConfirmMongoBatchAppend \
  :wow-benchmarks:generateMongoBatchAppendConfirmationReport \
  --no-parallel
```

For the append-path E2E gain decision, run the counterbalanced paired experiment:

```bash
./gradlew :wow-benchmarks:benchmarkMongoBatchAppendPairedE2E \
  :wow-benchmarks:generateMongoBatchAppendPairedE2EReport \
  --no-parallel
```

This runs eight pairs for each of one and four JMH threads, alternating AB (`insertOne → batch`) and BA
(`batch → insertOne`). Each leg is isolated in its own JMH process. The report calculates a Student-t 95%
confidence interval over paired `log(batch / insertOne)` ratios and passes the configured gain threshold only
when the unrounded lower bound is greater than `1.05×`. The measured append-path workload includes per-invocation
event-stream creation, the shared Reactor harness, `MongoEventStore.append`, Mongo driver/network/write
acknowledgement, and the final wait. It does not include Command Gateway ingress or downstream event processing.

Benchmark thread-level tasks also share a Gradle execution lock so they cannot load the same MongoDB service
concurrently when global Gradle parallel execution is enabled.

### Elasticsearch Batch Append Reports

```bash
./gradlew :wow-benchmarks:benchmarkQuickElasticsearchBatchAppend \
  :wow-benchmarks:generateElasticsearchBatchAppendBenchmarkReport \
  --no-parallel
```

Each workload writes 128 independent event streams and normalizes JMH throughput and average time per event.
The three paths are EventStore single `create`, native Bulk `create`, and coordinated Bulk `create` with
`maxSize=128` and `maxDelay=1ms`. The average-time score is the 128-event invocation wall time amortized
per event, not an independent single-request response latency. All three use the same `refresh` parameter, and the task runs both
`refresh=false` and `refresh=true`, so refresh cost is not hidden in only one comparison leg.

For multiple forks and JMH error intervals:

```bash
./gradlew :wow-benchmarks:benchmarkConfirmElasticsearchBatchAppend \
  :wow-benchmarks:generateElasticsearchBatchAppendConfirmationReport \
  --no-parallel
```

Both tasks require only Elasticsearch. They measure the EventStore append path through the local reactive
client; they do not include Command Gateway ingress, aggregate execution, or downstream event processing.
Quick results remain directional until a controlled multiple-fork confirmation
run is collected from a clean `HEAD`.

### Storage Batch Options Tuning

The tuning suites encode a candidate as `<maxSize>x<maxDelayMicros>us`. The
default scan covers `maxSize=16,32,64,128,256,512` and
`maxDelay=250µs,500µs,1ms,2ms,4ms`:

```bash
./gradlew :wow-benchmarks:benchmarkTuneMongoBatchOptions --no-parallel

./gradlew :wow-benchmarks:benchmarkTuneElasticsearchBatchOptions --no-parallel

./gradlew :wow-benchmarks:generateMongoBatchOptionsTuningReport \
  :wow-benchmarks:generateElasticsearchBatchOptionsTuningReport
```

Commit the tuning harness first and run both benchmarks from the same clean
`HEAD`; tuning tasks reject a dirty source tree. Run MongoDB and Elasticsearch
separately, then generate both reports only after both benchmark tasks have
finished so the first generated report cannot dirty the second run. Each candidate is measured through
the coordinated Store path with 1, 32, 128, and 512 concurrent appends; Elasticsearch
retains separate `refresh=false` and `refresh=true` rows. Screening point
estimates only select candidates. Keep the current `128x1000us` candidate in
confirmation and pass 2-3 distinct shortlisted pairs explicitly:

Review and commit both screening reports (or remove them and restore a clean
tree) before starting confirmation. Confirmation deliberately refuses to run
while generated-but-uncommitted screening reports make the source tree dirty.

```bash
./gradlew :wow-benchmarks:benchmarkConfirmMongoBatchOptions \
  -PbenchmarkConfirmMongoBatchOptionsParameters='batchOptions=128x1000us,256x500us' \
  --no-parallel

./gradlew :wow-benchmarks:benchmarkConfirmElasticsearchBatchOptions \
  -PbenchmarkConfirmElasticsearchBatchOptionsParameters='batchOptions=128x1000us,256x500us' \
  --no-parallel

./gradlew :wow-benchmarks:generateMongoBatchOptionsTuningConfirmationReport \
  :wow-benchmarks:generateElasticsearchBatchOptionsTuningConfirmationReport \
  -PbenchmarkConfirmMongoBatchOptionsParameters='batchOptions=128x1000us,256x500us' \
  -PbenchmarkConfirmElasticsearchBatchOptionsParameters='batchOptions=128x1000us,256x500us'
```

Select MongoDB and Elasticsearch defaults independently. A candidate must stay
within 5% of the best saturated throughput, avoid more than 10% regression for
isolated, burst32, representative128 workloads and allocation, and survive
multiple-fork confirmation at both one and four JMH producer threads.
Elasticsearch candidates must pass both refresh strata; `refresh=true`, the
Store default, is the primary ranking stratum. When candidates are statistically
indistinguishable, prefer the smaller batch and shorter delay. Confirmation
reports classify a candidate as `PASS`, `REGRESSION`, or `INCONCLUSIVE` by
conservatively combining independent JMH score intervals. An `INCONCLUSIVE`
candidate needs paired confirmation before changing the default. The current
average-time rows are not response-time percentiles, so do not increase the 1ms
delay based on this experiment alone without a dedicated low-load p99 budget.
`maxPending*` is an overload/backpressure limit, not a steady-state throughput
tuning parameter. SnapshotStore requires its own payload/coalescing benchmark
and must not inherit EventStore results.

## Reading The Report

- `thrpt` scores are throughput. Reports use decimal prefixes (`k`, `M`, `G`) so, for example, `1.57 k ops/s` means 1,570 operations per second. Higher is better.
- `avgt` scores are average time. Storage batch comparison suites normalize a 128-event invocation per event; tuning workloads use their declared 1/32/128/512 `@OperationsPerInvocation` counts. Values are amortized time per event rather than independently sampled response-time percentiles. Reports automatically select `ns/op`, `µs/op`, `ms/op`, or `s/op`.
- `gc.alloc.rate.norm` is normalized allocation per operation. Reports use binary prefixes (`KiB`, `MiB`, `GiB`); lower is usually better.
- `±` is the JMH-reported error. Compact units affect presentation only; reports retain raw precision for sorting, comparisons, and regression gates.
- Most Quick reports contain throughput and allocation results. Storage batch reports additionally run average-time mode so they expose end-to-end batching amortized time, but the short one-fork profile still has wider variance than confirmation or Baseline evidence. The coordinated-to-native delta includes batch formation and possible partial flushes; it is not a pure coordinator CPU-overhead measurement.
- `benchmarkCompare` first applies the configured point-estimate threshold, then uses baseline/current JMH error intervals to classify a threshold crossing. Non-overlapping intervals produce a regression or improvement candidate; overlapping or missing intervals remain inconclusive until a controlled confirmation run.
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
| Elasticsearch | `localhost:9200` |

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

For Elasticsearch, use the benchmark Docker profile:

```bash
docker compose \
  --env-file wow-benchmarks/docker/benchmark.env \
  -f wow-benchmarks/docker/compose.elasticsearch.yml up -d
```

The Elasticsearch profile disables security and persistent storage, uses tmpfs-backed
data, and configures a fixed heap for repeatable local write-path measurements.

Docker image tags, container names, host ports, tmpfs sizes, Mongo credentials,
healthcheck intervals, Docker log retention, the Mongo WiredTiger cache size, and the
Elasticsearch heap
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

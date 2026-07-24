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
| Scheduler HOL E2E | Four fixed aggregate IDs with one control lane and three independently reported fast lanes. | Separating stripe collision head-of-line blocking from Scheduler worker-rail contention. | `benchmarkCommandIngressHolE2E` |
| Multi-Aggregate Scheduler | Multiple dispatcher chains under per-aggregate or role-shared Scheduler ownership, with both configuration-equivalent and equal-total-worker controls. | Quantifying per-type pool amplification, work conservation, and noisy-neighbor isolation. | `benchmarkMultiAggregateScheduler`, `benchmarkMultiAggregateSchedulerFixedBudget`, `benchmarkMultiAggregateSchedulerIsolation` |
| Command PROCESSED Open Loop | Fixed absolute-time arrivals with bounded in-flight admission and deadline-aware PROCESSED results. | Finding sustainable processed throughput, queue growth, shedding, and tail latency without closed-loop coordinated omission. | `benchmarkCommandProcessedOpenLoop`, `benchmarkCommandProcessedOpenLoopSmoke` |
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

### Scheduler Head-of-Line Diagnosis

```bash
./gradlew :wow-benchmarks:benchmarkCommandIngressHolE2E --no-parallel
```

This independent diagnostic always uses one four-thread JMH group: one control aggregate and
three observed-fast aggregates. It runs three forks in both throughput and SampleTime modes,
with paired uniform/one-slow scenarios for:

- four aggregate IDs on four distinct stripes with four Scheduler workers
- four aggregate IDs colliding on one stripe with four Scheduler workers
- four distinct stripes sharing one Scheduler worker

Read `secondaryMetrics.observedFastAggregates`, not the mixed group primary metric, for fast-lane
throughput and p50/p95/p99. The fixed matrix uses 16 ordering stripes, four aggregate IDs, and
100,000 synthetic CPU tokens on the designated slow aggregate. To rerun only selected scenarios,
pass their enum names through `benchmarkCommandIngressHolParameters`, for example:

```bash
./gradlew :wow-benchmarks:benchmarkCommandIngressHolE2E \
  '-PbenchmarkCommandIngressHolParameters=contentionScenario=DISTINCT_UNIFORM_POOL4,DISTINCT_ONE_SLOW_POOL4' \
  --no-parallel
```

The task is a closed-loop, Noop-event-store scheduling diagnostic. It does not model open-loop
queue growth, persistence latency, production arrival distributions, or a real aggregate state
hotspot, and it is excluded from baseline comparison and grouped reports.

### Multi-Aggregate Scheduler Diagnosis

The command runtime creates one child dispatcher per named aggregate. The default Scheduler
supplier also creates one `newParallel(P)` pool per named aggregate, so `scheduler-pool-size=P`
is a per-type pool size rather than a role-wide worker budget. Use three separate tasks to avoid
mixing that resource amplification with topology effects:

```bash
./gradlew :wow-benchmarks:benchmarkMultiAggregateScheduler --no-parallel
./gradlew :wow-benchmarks:benchmarkMultiAggregateSchedulerFixedBudget --no-parallel
./gradlew :wow-benchmarks:benchmarkMultiAggregateSchedulerIsolation --no-parallel
```

`benchmarkMultiAggregateScheduler` keeps the configured pool size equal across topologies. Its
defaults compare aggregate type counts `1,4,16` with pool size `4`: dedicated ownership exposes
`A × 4` workers, while the benchmark-only role-shared counterfactual exposes four. This reproduces
the semantics and resource growth of the current configuration; it is not an equal-resource
causal comparison.

`benchmarkMultiAggregateSchedulerFixedBudget` holds the role capacity at 14 workers. Aggregate
type counts `1,2,7,14` divide that budget exactly: dedicated ownership receives `14 / A` workers
per pool, and role-shared ownership receives one 14-worker pool. The one-type rows are the negative
control because both topologies reduce to one Scheduler with the same capacity.

`benchmarkMultiAggregateSchedulerIsolation` uses two dispatcher chains and exactly two workers:
dedicated is `1 + 1`, role-shared is one pool of two. Its JMH groups are:

- `balanced`: four producers for Type A and four for Type B.
- `skewed`: one producer for Type A and seven for Type B.
- `typeACpuTokens=0`: uniform control.
- `typeACpuTokens=100000`: one-slow-type diagnostic.

Read the Type B secondary metric for isolation and noisy-neighbor effects, and the group primary
metric for total work conservation. Throughput mode reports per-type and total ops/s; SampleTime
reports their latency distributions independently. The fixture verifies Scheduler identity and
actual worker count, generates IDs on distinct stripes, and prewarms all four isolation groups
before measurement.

All three tasks use real `AggregateCommandDispatcher` chains but reuse identical Cart metadata,
messages, and completion handlers behind distinct logical type keys. This controls domain-handler
differences and isolates Scheduler ownership. They are closed-loop component diagnostics with no
gateway, event store, network, or production arrival process. In particular, the role-shared
Scheduler still uses Reactor's statically pinned `newParallel` rails; it does not test a
work-conserving shared executor. Do not replace the production default from these results alone.

Target one task independently when changing its matrix:

```bash
./gradlew :wow-benchmarks:benchmarkMultiAggregateScheduler \
  '-PbenchmarkMultiAggregateSchedulerParameters=aggregateTypeCount=1,4,16;schedulerTopology=DEDICATED_PER_AGGREGATE,SHARED_ROLE;schedulerPoolSize=4;aggregateIdCardinality=256;stripeCount=default' \
  --no-parallel

./gradlew :wow-benchmarks:benchmarkMultiAggregateSchedulerFixedBudget \
  '-PbenchmarkMultiAggregateSchedulerFixedBudgetParameters=aggregateTypeCount=1,2,7,14;schedulerTopology=DEDICATED_PER_AGGREGATE,SHARED_ROLE;roleWorkerBudget=14;aggregateIdCardinality=256;stripeCount=default' \
  --no-parallel

./gradlew :wow-benchmarks:benchmarkMultiAggregateSchedulerIsolation \
  '-PbenchmarkMultiAggregateSchedulerIsolationParameters=schedulerTopology=DEDICATED_PER_AGGREGATE,SHARED_ROLE;typeACpuTokens=0,100000;roleWorkerBudget=2' \
  --no-parallel
```

### Command PROCESSED Bounded Open Loop

Start with the low-load protocol check:

```bash
./gradlew :wow-benchmarks:benchmarkCommandProcessedOpenLoopSmoke --no-parallel
```

The formal task defaults to `200k,300k,350k,400k` commands/s, three isolated JVM repetitions per
rate, Scheduler pool `4`, stripe token `default`, 10 seconds of continuous warmup, 20 seconds of
measurement, 16 producer threads, and `maxInFlight=65536`. Pool `4` is an experimental matrix
default for historical comparability; it is not the production CPU-sized default:

```bash
./gradlew :wow-benchmarks:benchmarkCommandProcessedOpenLoop --no-parallel
```

Formal open-loop tasks reject a dirty source tree: the recorded commit must be sufficient to
rebuild the exact runner. They also force `observationMode=FULL`; a runner JSON field alone never
qualifies evidence as formal. Consumers must require a finalized `formal` SUCCESS manifest,
clean source, and matching runner/result/human hashes. The smoke task remains available while
developing local changes.

Symbolic Scheduler values are resolved before the child JVM starts. `cpu` becomes an explicit
pool size after applying `reactor.schedulers.defaultPoolSize`/`ActiveProcessorCount`, and
`default` becomes an explicit stripe count after applying `wow.parallelism`; the child receives
those numeric values and postflight checks them exactly.
The resolved values, producer/watchdog settings, JVM arguments, observation mode, and open-loop
orchestrator SHA are part of the protocol fingerprint and result filename. This prevents the same
`cpu/default` token from silently referring to different effective configurations.

Each producer owns a disjoint global arrival sequence and schedules against the same absolute
`System.nanoTime()` origin. Completion never advances the next arrival. Reaching `maxInFlight`
immediately records client-side shedding rather than waiting for a permit. Warmup and measurement
remain continuous, but results retain both scheduled-arrival cohorts and actual
measurement-window completion counts, including their cross-tabulation. The runner uses the real
last-result
`sendAndWait(command, CommandWait.processed(commandId))` path. A benchmark-only `CommandBus`
decorator observes successful `SENT` at the underlying `send()` completion. A paired
`CommandHandler` decorator tracks server work through handler termination, so client timeout does
not masquerade as a drained dispatcher. Timeout observation uses removable deadline buckets and a
fixed-delay sweep; completed requests are removed immediately instead of scanning all active
requests every 5 ms. The result reports ticket, sweep, candidate-visit, and sweep-duration counts
so this benchmark instrumentation remains visible.

Tune one dimension at a time:

```bash
./gradlew :wow-benchmarks:benchmarkCommandProcessedOpenLoop \
  -PbenchmarkOpenLoopRates=320000,340000,360000 \
  -PbenchmarkOpenLoopRepeats=3 \
  -PbenchmarkOpenLoopSchedulerPoolSize=4 \
  -PbenchmarkOpenLoopStripeCount=default \
  -PbenchmarkOpenLoopWarmupSeconds=10 \
  -PbenchmarkOpenLoopMeasurementSeconds=20 \
  -PbenchmarkOpenLoopProducerCount=16 \
  -PbenchmarkOpenLoopMaxInFlight=65536 \
  -PbenchmarkOpenLoopRequestTimeoutMillis=5000 \
  -PbenchmarkOpenLoopWatchdogIntervalMillis=5 \
  -PbenchmarkOpenLoopStartLeadMillis=250 \
  -PbenchmarkOpenLoopMaxGeneratorMissedRatio=0.001 \
  -PbenchmarkOpenLoopMaxGeneratorLagP99Millis=5 \
  -PbenchmarkOpenLoopAggregateCardinality=high \
  --no-parallel
```

The formal defaults reject a run when any arrival expires before admission, the measurement
generator misses more than 0.1% of planned arrivals, or admitted-arrival lag p99 exceeds 5 ms.
These are experiment-validity guardrails, not application latency SLOs; change the configurable
miss/lag limits explicitly when a different protocol is intended. The generator still shares the
benchmark JVM and host CPUs, so accepted results remain instrumented capacity measurements rather
than an external-generator production model.

Interpret the result as a capacity point only when all of the following hold:

- `processedByDeadlinePerPlanned` meets the workload SLO and shedding/timeouts are acceptable.
- measurement-window processed throughput tracks the offered rate.
- first/last measurement-decile in-flight means do not show sustained growth.
- generator missed/expired counts and generator-lag percentiles stay small enough that the
  co-located load generator is not the bottleneck.
- final client in-flight, deadline registrations, server outstanding work, and active server
  tickets are all zero without forced close.
- timeout-observer candidate visits and sweep time remain small relative to the measured load.

Latency percentiles are conditional: generator lag covers admitted measurement arrivals,
SENT latency covers sent arrivals, and processed latency covers successful completions before
their half-open deadline (`completion < deadline`). When processed yield is above 99%, the JSON
also reports an empirical all-offered p99 at successful-completion rank `99 / yield`; at or below
99% yield that value is deadline-censored and reported as unavailable. Because successful
`send()` completion can be observed after a very fast handler has already emitted `PROCESSED`,
negative SENT-to-PROCESSED observations are counted explicitly and excluded from that conditional
latency distribution.
The Noop event store measures a framework ceiling, not Redis/Mongo or production capacity.

Audit benchmark observer sensitivity separately:

```bash
./gradlew :wow-benchmarks:benchmarkCommandProcessedOpenLoopObservationDiagnostic \
  -PbenchmarkOpenLoopObservationModes=FULL,NO_DEADLINE_WHEEL,NO_SERVER_TRACKER,GENERATOR_ONLY_LATENCY,NO_LATENCY \
  -PbenchmarkOpenLoopObservationRate=340000 \
  -PbenchmarkOpenLoopObservationWarmupSeconds=5 \
  -PbenchmarkOpenLoopObservationMeasurementSeconds=10 \
  -PbenchmarkOpenLoopObservationRepeats=10 \
  --no-parallel
```

The default five-mode design requires ten repetitions: a complete Williams-balanced block puts
every mode in every position twice and balances every directed predecessor pair twice. An even
number of modes requires one repetition per mode; an odd number requires twice that count.
The predecessor balance is within each row; transitions between isolated JVM rows are not part of
the design. `benchmarkOpenLoopObservationRepeats` must contain whole blocks. Every leaf manifest
records the canonical mode set, block size, row sequence, position, design ID, source fingerprint,
and protocol fingerprint. The source fingerprint covers code/build inputs and every source-set
resource, including extensionless `META-INF/services` descriptors and XML resources. The aggregate
task publishes a block manifest only after every expected leaf has the same run ID, source
commit/dirty identity, runner source, and runner JAR; each leaf must have finalized SUCCESS and its
result/human size and SHA-256 are rechecked from disk. An incomplete or tampered run leaves only an
`IN_PROGRESS` block manifest.

- `FULL` is the only complete timeout, server-drain, generator-fidelity, and latency protocol.
- `GENERATOR_ONLY_LATENCY` and `NO_LATENCY` disable only recorder storage; clocks, request state,
  wrappers, lookups, and terminal hooks remain.
- `NO_SERVER_TRACKER` removes ticket state/drain invariants but leaves bus/handler wrappers and
  terminal hooks installed.
- `NO_DEADLINE_WHEEL` changes timeout release and admission when requests cross their deadline.
  It is a semantic ablation, not a guaranteed throughput upper bound.

Non-FULL `SUCCESS` means only that the selected mode's local invariants passed. A mode without
generator latency also lacks the lag gate. Analyze only a complete block for which every mode has
a finalized SUCCESS manifest; dropping a failed FULL row while retaining lighter modes creates
survivor bias. These diagnostics must not be promoted to capacity evidence.

The recorder write path has its own JMH task:

```bash
./gradlew :wow-benchmarks:benchmarkOpenLoopObserverComponent \
  -PbenchmarkOpenLoopObserverThreads=1,4,16 \
  -PbenchmarkOpenLoopObserverModes=avgt \
  --no-parallel
```

It compares no observation, disabled recorder calls, generator-only recording, and five enabled
recorders with the GC profiler. It intentionally excludes clocks, request state, deadline
tracking, summary merging, and the command runtime, so it can explain only the recorder component
of an end-to-end observation delta.

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
| `wow-benchmarks/results/jmh/scheduler-hol/command-ingress-hol-e2e/` | Local Scheduler HOL throughput/SampleTime JSON, human output, and manifest. | Do not commit generated run output; preserve reviewed evidence separately when needed. |
| `wow-benchmarks/results/jmh/` | Local JMH JSON and human-readable outputs. | Do not commit generated run output. |
| `wow-benchmarks/results/open-loop/command-processed/` | Local bounded-open-loop JSON, compact Markdown, and independent provenance manifests. | Do not commit generated run output; preserve reviewed evidence separately when needed. |
| `wow-benchmarks/results/baselines/framework-e2e.json` | Framework E2E comparison baseline, when present. | Commit only intentional baseline updates. |

Files under `results/reports/*.md` are generated. Do not hand-edit benchmark rows; rerun the benchmark/report task instead.
Every successful thread-level JMH run writes a neighboring `*.manifest.json` sidecar with the source commit and dirty state, run specification, resolved required-service endpoints, profiler arguments, runtime, and SHA-256 digests for the JSON and human output. Failed runs do not publish a success manifest. Report and comparison tasks reject raw results with missing, mixed, or mismatched manifests.
Infrastructure reports validate captured service names against the suite identity and display the captured host/port provenance. They do not reinterpret historical evidence using the report-time Redis or MongoDB environment variables.

## Reading The Report

- `thrpt` scores are throughput. Reports use decimal prefixes (`k`, `M`, `G`) so, for example, `1.57 k ops/s` means 1,570 operations per second. Higher is better.
- `avgt` scores are average latency. Reports automatically select `ns/op`, `µs/op`, `ms/op`, or `s/op`.
- `sample` rows retain p50/p95/p99 under `scorePercentiles`; JMH group method metrics are under `secondaryMetrics`. The generic grouped-report parser does not turn these into HOL conclusions.
- `gc.alloc.rate.norm` is normalized allocation per operation. Reports use binary prefixes (`KiB`, `MiB`, `GiB`); lower is usually better.
- `±` is the JMH-reported error. Compact units affect presentation only; reports retain raw precision for sorting, comparisons, and regression gates.
- Quick reports contain throughput and allocation results only. They are useful for local regression checks, but the shorter run profile has wider variance than Baseline E2E.
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

Scheduler HOL intentionally fixes four threads because `@GroupThreads(1) + @GroupThreads(3)`
defines one experimental group. Its thread count and `thrpt,sample` modes are not configurable.
Only `benchmarkCommandIngressHolParameters` is exposed for selecting a subset of the six
predefined scenarios.

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

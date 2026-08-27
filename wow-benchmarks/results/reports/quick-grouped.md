# Quick Grouped Benchmark Report

## Policy
- Quick results are directional feedback; run Baseline E2E before updating baselines or claiming framework performance conclusions.
- Framework E2E results isolate command pipeline overhead with in-memory or noop stores; they are not production persistence capacity.
- Single-command blocking rows are synchronous round-trip regression controls. Use Batch CommandWrite Sequential c1 as the primary framework-cost signal.
- Infrastructure E2E results reflect real Redis or Mongo persistence paths when services are available.
- No-snapshot growing-stream scenarios are diagnostics for replay pressure, not default E2E goals.
- Component results explain bottlenecks and are not standalone performance goals.
- Smoke results are excluded from performance reports.

## Reading Values

- Throughput uses decimal prefixes: `k` = 1,000, `M` = 1,000,000, `G` = 1,000,000,000.
- Allocation uses binary prefixes: `KiB` = 1,024 bytes, `MiB` = 1,048,576 bytes.
- Every displayed score and error keeps its scaled unit attached, for example `1.57 k ops/s`.
- Average-time results are automatically scaled to `ns/op`, `µs/op`, `ms/op`, or `s/op`.
- `±` is the JMH-reported error. Scaling changes presentation only; calculations keep raw precision.

## Benchmark Run Provenance
- **Source Commit**: `adcf1080df0d2bac2a8cca2e9f63ec0302da3d2a`
- **Source Dirty**: `false`
- **Project Version**: `8.10.4`
- **JMH Jar SHA-256**: `85ddc559cd882343e027133cb2c9e3db27632711420132bf97860168d7aad93a`
- **Runtime JVM**: OpenJDK 64-Bit Server VM 17.0.7+7-LTS / Java 17.0.7
- **Runtime OS**: Mac OS X 26.5.2 aarch64
- **CPU Cores**: 14
- **Physical Memory**: 24.0 GiB

### Manifest-bound Run-Time Infrastructure

- **Captured At**: 2026-08-05T06:29:30.130866Z to 2026-08-05T06:38:12.090971Z
- **Benchmark Client**: host JVM
- **Docker Server**: not required by these suites
- **Local Docker Containers**: none required; service endpoints remain bound in each run manifest.

| Suite | Profile | Threads | Run ID | Started | Completed | Profilers | Rows | Result SHA-256 |
|-------|---------|---------|--------|---------|-----------|-----------|------|----------------|
| component | quick | 1 | `793e57c4-3374-44ea-a1c5-0d57f312c8d8` | 2026-08-05T06:32:49.432560Z | 2026-08-05T06:36:50.609731Z | `-prof gc` | 27 | `724e0e0d4a0fbb379396d5e30e78ce6325465fe31ad0098816504581400fcca6` |
| framework-e2e | quick | 1 | `793e57c4-3374-44ea-a1c5-0d57f312c8d8` | 2026-08-05T06:29:30.130920Z | 2026-08-05T06:31:09.599744Z | `-prof gc` | 8 | `d22310aac5de5aa348b71d943dd7a30291a6ba68706580558bddedf0ebea23cd` |
| framework-e2e | quick | 4 | `793e57c4-3374-44ea-a1c5-0d57f312c8d8` | 2026-08-05T06:31:09.663049Z | 2026-08-05T06:32:49.372824Z | `-prof gc` | 8 | `7669834e4af0f8a467af1b751fa2d188cb3244369bacb3f80fd51eb4d7bd2eec` |
| webflux | quick | 1 | `793e57c4-3374-44ea-a1c5-0d57f312c8d8` | 2026-08-05T06:36:50.676489Z | 2026-08-05T06:37:31.243716Z | `-prof gc` | 15 | `e92c518426eaf6efab022b518de28edbe6c3526259f051a97b018d818fb021e4` |
| webflux | quick | 4 | `793e57c4-3374-44ea-a1c5-0d57f312c8d8` | 2026-08-05T06:37:31.288159Z | 2026-08-05T06:38:12.091083Z | `-prof gc` | 15 | `0fcef87d5ef1dfa8568f00c70690c4397a4ebb8491b053861a4213c6c3e60187` |

## Report Generation Environment
- **Version**: 8.10.4
- **JVM**: OpenJDK 64-Bit Server VM 17.0.7+7-LTS
- **OS**: Mac OS X 26.5.2 aarch64
- **Generated At**: 2026-08-05T14:38:12+08:00
- **CPU Cores**: 14
- **Physical Memory**: 24.0 GiB
- **Benchmark JVM Args**: see per-suite Run Profiles below

## Run Profiles

- **Primary Framework E2E**: warmup=1x2s, measurement=2x3s, fork=1, threads=1,4, modes=thrpt, profilers=gc, jvmArgs=`-Xmx1g -Xms1g -XX:+UseG1GC`
- **Infrastructure E2E**: warmup=1x2s, measurement=2x3s, fork=1, threads=1,4, modes=thrpt, profilers=gc, jvmArgs=`-Xmx1g -Xms1g -XX:+UseG1GC`
- **Component**: warmup=1x2s, measurement=2x3s, fork=1, threads=1, modes=thrpt, profilers=gc, jvmArgs=`-Xmx1g -Xms1g -XX:+UseG1GC`
- **WebFlux Adapter**: warmup=0, measurement=1x2s, fork=1, threads=1,4, modes=thrpt, profilers=gc, jvmArgs=`-Xmx1g -Xms1g -XX:+UseG1GC`

## Framework E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error |
|-------|---------|-----------|-------|-------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 61.79 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 66.42 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 66.79 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 119.43 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 125.13 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 125.29 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 130.33 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 130.75 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 141.99 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 142 k ops/s | - |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 14.25 KiB/op | - | 130.33 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 14.12 KiB/op | - | 125.29 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13.97 KiB/op | - | 141.99 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13.97 KiB/op | - | 142 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 13.04 KiB/op | - | 148.32 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.96 KiB/op | - | 175.77 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 5.69 KiB/op | - | 66.79 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 5.56 KiB/op | - | 125.13 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 5.47 KiB/op | - | 61.79 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 5.36 KiB/op | - | 119.43 k ops/s |

## Component Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error |
|-------|---------|-----------|-------|-------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | 65.81 k ops/s | - |
| Component | 1 | CommandDispatcherChainComponentBenchmark.dispatchSingleHotAggregateThroughChain (handlerCost=NOOP, schedulerStrategy=PARALLEL) | 178.33 k ops/s | - |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 468.13 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 469.23 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 568.9 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 582.13 k ops/s | - |
| Component | 1 | SerializationComponentBenchmark.commandSerializeDeserialize | 655.09 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 691.01 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateOnly | 791.8 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 901.83 k ops/s | - |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | thrpt | 70.93 KiB/op | - | 65.81 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10.29 KiB/op | - | 469.23 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | thrpt | 8.58 KiB/op | - | 582.13 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | thrpt | 8.52 KiB/op | - | 568.9 k ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | thrpt | 7.77 KiB/op | - | 468.13 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | thrpt | 7.31 KiB/op | - | 691.01 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateOnly | thrpt | 6.95 KiB/op | - | 791.8 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | thrpt | 5.9 KiB/op | - | 901.83 k ops/s |
| Component | 1 | SerializationComponentBenchmark.commandSerializeDeserialize | thrpt | 5.55 KiB/op | - | 655.09 k ops/s |
| Component | 1 | AggregateRepositoryLoadComponentBenchmark.loadSnapshot | thrpt | 5.14 KiB/op | - | 1.05 M ops/s |

## WebFlux Adapter Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error |
|-------|---------|-----------|-------|-------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1.58 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 5.18 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 5.54 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 8.76 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 20.06 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 20.08 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 26.11 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 32.98 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 50.47 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=10, traceWindowSize=10) | 59.69 k ops/s | - |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2.85 MiB/op | - | 1.58 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2.83 MiB/op | - | 5.18 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 746.02 KiB/op | - | 5.54 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 738.42 KiB/op | - | 20.08 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 439.38 KiB/op | - | 8.76 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 435.86 KiB/op | - | 26.11 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 165.36 KiB/op | - | 20.06 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 162.88 KiB/op | - | 50.47 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 79.46 KiB/op | - | 32.98 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 78.1 KiB/op | - | 107.75 k ops/s |

## Group Details

### Primary Framework E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error |
|-------|---------|-----------|-------|-------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 61.79 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 66.42 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 66.79 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 119.43 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 125.13 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 125.29 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 130.33 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 130.75 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 141.99 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 142 k ops/s | - |

### Primary Framework E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 14.25 KiB/op | - | 130.33 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 14.12 KiB/op | - | 125.29 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13.97 KiB/op | - | 141.99 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13.97 KiB/op | - | 142 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 13.04 KiB/op | - | 148.32 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.96 KiB/op | - | 175.77 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 5.69 KiB/op | - | 66.79 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 5.56 KiB/op | - | 125.13 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 5.47 KiB/op | - | 61.79 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 5.36 KiB/op | - | 119.43 k ops/s |

### Component Lowest Throughput

| Suite | Threads | Benchmark | Score | Error |
|-------|---------|-----------|-------|-------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | 65.81 k ops/s | - |
| Component | 1 | CommandDispatcherChainComponentBenchmark.dispatchSingleHotAggregateThroughChain (handlerCost=NOOP, schedulerStrategy=PARALLEL) | 178.33 k ops/s | - |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 468.13 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 469.23 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 568.9 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 582.13 k ops/s | - |
| Component | 1 | SerializationComponentBenchmark.commandSerializeDeserialize | 655.09 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 691.01 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateOnly | 791.8 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 901.83 k ops/s | - |

### Component Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | thrpt | 70.93 KiB/op | - | 65.81 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10.29 KiB/op | - | 469.23 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | thrpt | 8.58 KiB/op | - | 582.13 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | thrpt | 8.52 KiB/op | - | 568.9 k ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | thrpt | 7.77 KiB/op | - | 468.13 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | thrpt | 7.31 KiB/op | - | 691.01 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateOnly | thrpt | 6.95 KiB/op | - | 791.8 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | thrpt | 5.9 KiB/op | - | 901.83 k ops/s |
| Component | 1 | SerializationComponentBenchmark.commandSerializeDeserialize | thrpt | 5.55 KiB/op | - | 655.09 k ops/s |
| Component | 1 | AggregateRepositoryLoadComponentBenchmark.loadSnapshot | thrpt | 5.14 KiB/op | - | 1.05 M ops/s |

### WebFlux Adapter Lowest Throughput

| Suite | Threads | Benchmark | Score | Error |
|-------|---------|-----------|-------|-------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1.58 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 5.18 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 5.54 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 8.76 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 20.06 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 20.08 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 26.11 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 32.98 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 50.47 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=10, traceWindowSize=10) | 59.69 k ops/s | - |

### WebFlux Adapter Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2.85 MiB/op | - | 1.58 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2.83 MiB/op | - | 5.18 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 746.02 KiB/op | - | 5.54 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 738.42 KiB/op | - | 20.08 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 439.38 KiB/op | - | 8.76 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 435.86 KiB/op | - | 26.11 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 165.36 KiB/op | - | 20.06 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 162.88 KiB/op | - | 50.47 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 79.46 KiB/op | - | 32.98 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 78.1 KiB/op | - | 107.75 k ops/s |

## Primary Framework E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickE2E`
- **JMH Config**: warmup=1x2s, measurement=2x3s, fork=1, threads=1,4, modes=thrpt, profilers=gc
- **Formal Regression Source**: no
- **Source Row Count**: 16
- **Parsed Row Count**: 16

- **threads=1 Result File**: ` wow-benchmarks/results/jmh/quick/framework-e2e/threads-1-framework-e2e.json `
  - Last Modified: 2026-08-05T06:31:09.568Z
- **threads=4 Result File**: ` wow-benchmarks/results/jmh/quick/framework-e2e/threads-4-framework-e2e.json `
  - Last Modified: 2026-08-05T06:32:49.353Z

| Suite | Benchmark | Threads | Mode | Score | Error | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|-------------------|
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 1 | thrpt | 293.48 k ops/s | - | 3.95 KiB/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 4 | thrpt | 338.75 k ops/s | - | 3.94 KiB/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 1 | thrpt | 274.78 k ops/s | - | 4.3 KiB/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 4 | thrpt | 334.66 k ops/s | - | 4.43 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 175.77 k ops/s | - | 12.96 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 148.32 k ops/s | - | 13.04 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 1 | thrpt | 66.42 k ops/s | - | 4.69 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 4 | thrpt | 130.75 k ops/s | - | 4.71 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 125.29 k ops/s | - | 14.12 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 130.33 k ops/s | - | 14.25 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 1 | thrpt | 61.79 k ops/s | - | 5.47 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 4 | thrpt | 119.43 k ops/s | - | 5.36 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 141.99 k ops/s | - | 13.97 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 142 k ops/s | - | 13.97 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 1 | thrpt | 66.79 k ops/s | - | 5.69 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 4 | thrpt | 125.13 k ops/s | - | 5.56 KiB/op |

## Infrastructure E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E`
- **JMH Config**: warmup=1x2s, measurement=2x3s, fork=1, threads=1,4, modes=thrpt, profilers=gc
- **Formal Regression Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: ` wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-1-infrastructure-e2e.json `
- **threads=4 Result File**: ` wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-4-infrastructure-e2e.json `

Status: unavailable. Result files were not present. Run benchmarkQuickInfrastructureE2E to include this optional group.

## Component Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickComponent`
- **JMH Config**: warmup=1x2s, measurement=2x3s, fork=1, threads=1, modes=thrpt, profilers=gc
- **Formal Regression Source**: no
- **Source Row Count**: 27
- **Parsed Row Count**: 27

- **threads=1 Result File**: ` wow-benchmarks/results/jmh/quick/component/threads-1-component.json `
  - Last Modified: 2026-08-05T06:36:50.598Z

| Suite | Benchmark | Threads | Mode | Score | Error | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|-------------------|
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 1 | thrpt | 1.18 M ops/s | - | 5.02 KiB/op |
| Component | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=10) | 1 | thrpt | 3.12 M ops/s | - | 2.02 KiB/op |
| Component | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | 1 | thrpt | 65.81 k ops/s | - | 70.93 KiB/op |
| Component | AggregateRepositoryLoadComponentBenchmark.loadEmptyStateAggregate | 1 | thrpt | 6.05 M ops/s | - | 1.3 KiB/op |
| Component | AggregateRepositoryLoadComponentBenchmark.loadSnapshot | 1 | thrpt | 1.05 M ops/s | - | 5.14 KiB/op |
| Component | CommandDispatcherChainComponentBenchmark.dispatchSingleHotAggregateThroughChain (handlerCost=NOOP, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 4.64 M ops/s | - | 672.05 B/op |
| Component | CommandDispatcherChainComponentBenchmark.dispatchSingleHotAggregateThroughChain (handlerCost=NOOP, schedulerStrategy=PARALLEL) | 1 | thrpt | 178.33 k ops/s | - | 807.19 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 1 | thrpt | 16.82 M ops/s | - | 272 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 1 | thrpt | 5.08 M ops/s | - | 1.05 KiB/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 1 | thrpt | 679.39 M ops/s | - | 2.7e-07 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 1 | thrpt | 469.23 k ops/s | - | 10.29 KiB/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 1 | thrpt | 568.9 k ops/s | - | 8.52 KiB/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 1 | thrpt | 691.01 k ops/s | - | 7.31 KiB/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 1 | thrpt | 582.13 k ops/s | - | 8.58 KiB/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 1 | thrpt | 791.8 k ops/s | - | 6.95 KiB/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 1 | thrpt | 901.83 k ops/s | - | 5.9 KiB/op |
| Component | CommandValidationComponentBenchmark.validateCommandBody | 1 | thrpt | 9.51 M ops/s | - | 408.04 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 1 | thrpt | 24.95 M ops/s | - | 112 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryNewAggregateEventStream | 1 | thrpt | 15.05 M ops/s | - | 465.8 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 1 | thrpt | 2.91 G ops/s | - | 6.3e-08 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 1 | thrpt | 20.7 M ops/s | - | 192.45 B/op |
| Component | MongoDocumentComponentBenchmark.eventStreamToDocument | 1 | thrpt | 1.27 M ops/s | - | 4.23 KiB/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 1 | thrpt | 655.09 k ops/s | - | 5.55 KiB/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 1 | thrpt | 468.13 k ops/s | - | 7.77 KiB/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 1 | thrpt | 3.7 M ops/s | - | 1.41 KiB/op |
| Component | WaitNotifyComponentBenchmark.registerWaitRegistration | 1 | thrpt | 32.06 M ops/s | - | 320 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 1 | thrpt | 3.04 M ops/s | - | 1.72 KiB/op |

## WebFlux Adapter Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickWebFlux`
- **JMH Config**: warmup=0, measurement=1x2s, fork=1, threads=1,4, modes=thrpt, profilers=gc
- **Formal Regression Source**: no
- **Source Row Count**: 30
- **Parsed Row Count**: 30

- **threads=1 Result File**: ` wow-benchmarks/results/jmh/quick/webflux/threads-1-webflux.json `
  - Last Modified: 2026-08-05T06:37:31.224Z
- **threads=4 Result File**: ` wow-benchmarks/results/jmh/quick/webflux/threads-4-webflux.json `
  - Last Modified: 2026-08-05T06:38:12.071Z

| Suite | Benchmark | Threads | Mode | Score | Error | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|-------------------|
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 1 | thrpt | 32.98 k ops/s | - | 79.46 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 4 | thrpt | 107.75 k ops/s | - | 78.1 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 1 | thrpt | 20.06 k ops/s | - | 165.36 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 4 | thrpt | 50.47 k ops/s | - | 162.88 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 1 | thrpt | 5.54 k ops/s | - | 746.02 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 4 | thrpt | 20.08 k ops/s | - | 738.42 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=1, traceWindowSize=10) | 1 | thrpt | 803.16 k ops/s | - | 3.29 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=1, traceWindowSize=10) | 4 | thrpt | 2.33 M ops/s | - | 3.25 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 1 | thrpt | 62.4 k ops/s | - | 37.12 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 4 | thrpt | 211.96 k ops/s | - | 36.55 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1 | thrpt | 1.58 k ops/s | - | 2.85 MiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 4 | thrpt | 5.18 k ops/s | - | 2.83 MiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=1, traceWindowSize=10) | 1 | thrpt | 724.09 k ops/s | - | 3.82 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=1, traceWindowSize=10) | 4 | thrpt | 2.1 M ops/s | - | 3.78 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=10, traceWindowSize=10) | 1 | thrpt | 59.69 k ops/s | - | 37.64 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=10, traceWindowSize=10) | 4 | thrpt | 194.13 k ops/s | - | 37.06 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 1 | thrpt | 8.76 k ops/s | - | 439.38 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 4 | thrpt | 26.11 k ops/s | - | 435.86 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.commandResultJsonServerResponseOnly | 1 | thrpt | 802.38 k ops/s | - | 4.4 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.commandResultJsonServerResponseOnly | 4 | thrpt | 2.28 M ops/s | - | 4.35 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.extractPreparedCommandMessage | 1 | thrpt | 3.62 M ops/s | - | 1.35 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.extractPreparedCommandMessage | 4 | thrpt | 6.83 M ops/s | - | 1.33 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.handlePreparedAddCartItemRequestWaitSent | 1 | thrpt | 111.01 k ops/s | - | 11.12 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.handlePreparedAddCartItemRequestWaitSent | 4 | thrpt | 204.08 k ops/s | - | 10.88 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.sendWaitSentCoreFromExtractedMessage | 1 | thrpt | 283.68 k ops/s | - | 4.66 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.sendWaitSentCoreFromExtractedMessage | 4 | thrpt | 310.38 k ops/s | - | 4.63 KiB/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseServerResponseOnly | 1 | thrpt | 1.79 M ops/s | - | 3.28 KiB/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseServerResponseOnly | 4 | thrpt | 6.47 M ops/s | - | 3.28 KiB/op |
| WebFlux Adapter | WebFluxResponseBenchmark.fluxJsonStreamingArrayServerResponseOnly | 1 | thrpt | 5.05 M ops/s | - | 1.04 KiB/op |
| WebFlux Adapter | WebFluxResponseBenchmark.fluxJsonStreamingArrayServerResponseOnly | 4 | thrpt | 20.2 M ops/s | - | 1.03 KiB/op |

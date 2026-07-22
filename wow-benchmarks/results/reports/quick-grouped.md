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
- Average latency is automatically scaled to `ns/op`, `µs/op`, `ms/op`, or `s/op`.
- `±` is the JMH-reported error. Scaling changes presentation only; calculations keep raw precision.

## Benchmark Run Provenance
- **Source Commit**: `07fcba8b412f7250b547425289ad0ef218ba8bde`
- **Source Dirty**: `false`
- **Project Version**: `8.9.0`
- **JMH Jar SHA-256**: `1d5902b9f334f5f771736a2933ad577f157440513808ff2bd5a15179acf3332e`
- **Runtime JVM**: OpenJDK 64-Bit Server VM 17.0.7+7-LTS / Java 17.0.7
- **Runtime OS**: Mac OS X 26.5.2 aarch64
- **CPU Cores**: 14
- **Physical Memory**: 24.0 GiB

| Suite | Profile | Threads | Run ID | Started | Completed | Profilers | Rows | Result SHA-256 |
|-------|---------|---------|--------|---------|-----------|-----------|------|----------------|
| component | quick | 1 | `b1bd4033-0cc9-4f9c-9d2a-404d7f7596c9` | 2026-07-22T10:16:20.146641Z | 2026-07-22T10:20:12.158117Z | `-prof gc` | 27 | `db6973eb1d06dde28b75f4a39358661076912f31a60b9cdc7489fa591e2f7da6` |
| framework-e2e | quick | 1 | `b1bd4033-0cc9-4f9c-9d2a-404d7f7596c9` | 2026-07-22T10:14:00.699165Z | 2026-07-22T10:15:10.471937Z | `-prof gc` | 8 | `fc45780e80d034f074c8d48f2f0cd9128631f8dabe252cc1e616545066348c54` |
| framework-e2e | quick | 4 | `b1bd4033-0cc9-4f9c-9d2a-404d7f7596c9` | 2026-07-22T10:15:10.513408Z | 2026-07-22T10:16:20.101418Z | `-prof gc` | 8 | `eb1d170249cfa308d4b4ad780ccb4b68402ef70e9f95be8571cee1e610a1753f` |
| webflux | quick | 1 | `b1bd4033-0cc9-4f9c-9d2a-404d7f7596c9` | 2026-07-22T10:20:12.211184Z | 2026-07-22T10:20:53.094047Z | `-prof gc` | 15 | `ecac86721ed4c2b6661b826f7873d7389df29798ec6f283de23c687117a99ef4` |
| webflux | quick | 4 | `b1bd4033-0cc9-4f9c-9d2a-404d7f7596c9` | 2026-07-22T10:20:53.135051Z | 2026-07-22T10:21:34.267333Z | `-prof gc` | 15 | `3524838dc170d0e096898884580397abb16fb8e825839bb842b705993804d91c` |

## Report Generation Environment
- **Version**: 8.9.0
- **JVM**: OpenJDK 64-Bit Server VM 17.0.7+7-LTS
- **OS**: Mac OS X 26.5.2 aarch64
- **Generated At**: 2026-07-22T21:36:47+08:00
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
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 81.13 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 91.3 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 91.97 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 133.95 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 143 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 147.17 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 158.98 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 167.47 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 208.21 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 224.32 k ops/s | - |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13.22 KiB/op | - | 143 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13.18 KiB/op | - | 208.21 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13.03 KiB/op | - | 167.47 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13 KiB/op | - | 249.84 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.09 KiB/op | - | 224.32 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.08 KiB/op | - | 302.78 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4.8 KiB/op | - | 91.97 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4.79 KiB/op | - | 147.17 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4.58 KiB/op | - | 81.13 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4.51 KiB/op | - | 133.95 k ops/s |

## Component Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error |
|-------|---------|-----------|-------|-------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | 74.47 k ops/s | - |
| Component | 1 | CommandDispatcherChainComponentBenchmark.dispatchSingleHotAggregateThroughChain (handlerCost=NOOP, schedulerStrategy=PARALLEL) | 120.04 k ops/s | - |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 450.57 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 455.68 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 564.96 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 574.93 k ops/s | - |
| Component | 1 | SerializationComponentBenchmark.commandSerializeDeserialize | 607.53 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 684.28 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateOnly | 769.14 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 859.76 k ops/s | - |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | thrpt | 55.3 KiB/op | - | 74.47 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10.27 KiB/op | - | 455.68 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | thrpt | 8.66 KiB/op | - | 574.93 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | thrpt | 8.63 KiB/op | - | 564.96 k ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | thrpt | 7.73 KiB/op | - | 450.57 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | thrpt | 7.34 KiB/op | - | 684.28 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateOnly | thrpt | 6.98 KiB/op | - | 769.14 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | thrpt | 5.89 KiB/op | - | 859.76 k ops/s |
| Component | 1 | SerializationComponentBenchmark.commandSerializeDeserialize | thrpt | 5.52 KiB/op | - | 607.53 k ops/s |
| Component | 1 | AggregateRepositoryLoadComponentBenchmark.loadSnapshot | thrpt | 5.14 KiB/op | - | 1.05 M ops/s |

## WebFlux Adapter Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error |
|-------|---------|-----------|-------|-------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1.57 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 5.26 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 5.58 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 8.96 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 18.11 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 19.65 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 28.55 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 34.35 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 47.37 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 64.51 k ops/s | - |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2.84 MiB/op | - | 1.57 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2.82 MiB/op | - | 5.26 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 742.58 KiB/op | - | 5.58 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 737.23 KiB/op | - | 19.65 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 435.8 KiB/op | - | 8.96 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 434 KiB/op | - | 34.35 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 165.09 KiB/op | - | 18.11 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 162.48 KiB/op | - | 47.37 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 79.81 KiB/op | - | 28.55 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 78.07 KiB/op | - | 110.84 k ops/s |

## Group Details

### Primary Framework E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error |
|-------|---------|-----------|-------|-------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 81.13 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 91.3 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 91.97 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 133.95 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 143 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 147.17 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 158.98 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 167.47 k ops/s | - |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 208.21 k ops/s | - |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 224.32 k ops/s | - |

### Primary Framework E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13.22 KiB/op | - | 143 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13.18 KiB/op | - | 208.21 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13.03 KiB/op | - | 167.47 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13 KiB/op | - | 249.84 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.09 KiB/op | - | 224.32 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.08 KiB/op | - | 302.78 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4.8 KiB/op | - | 91.97 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4.79 KiB/op | - | 147.17 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4.58 KiB/op | - | 81.13 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4.51 KiB/op | - | 133.95 k ops/s |

### Component Lowest Throughput

| Suite | Threads | Benchmark | Score | Error |
|-------|---------|-----------|-------|-------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | 74.47 k ops/s | - |
| Component | 1 | CommandDispatcherChainComponentBenchmark.dispatchSingleHotAggregateThroughChain (handlerCost=NOOP, schedulerStrategy=PARALLEL) | 120.04 k ops/s | - |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 450.57 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 455.68 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 564.96 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 574.93 k ops/s | - |
| Component | 1 | SerializationComponentBenchmark.commandSerializeDeserialize | 607.53 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 684.28 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateOnly | 769.14 k ops/s | - |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 859.76 k ops/s | - |

### Component Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | thrpt | 55.3 KiB/op | - | 74.47 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10.27 KiB/op | - | 455.68 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | thrpt | 8.66 KiB/op | - | 574.93 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | thrpt | 8.63 KiB/op | - | 564.96 k ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | thrpt | 7.73 KiB/op | - | 450.57 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | thrpt | 7.34 KiB/op | - | 684.28 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateOnly | thrpt | 6.98 KiB/op | - | 769.14 k ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | thrpt | 5.89 KiB/op | - | 859.76 k ops/s |
| Component | 1 | SerializationComponentBenchmark.commandSerializeDeserialize | thrpt | 5.52 KiB/op | - | 607.53 k ops/s |
| Component | 1 | AggregateRepositoryLoadComponentBenchmark.loadSnapshot | thrpt | 5.14 KiB/op | - | 1.05 M ops/s |

### WebFlux Adapter Lowest Throughput

| Suite | Threads | Benchmark | Score | Error |
|-------|---------|-----------|-------|-------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1.57 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 5.26 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 5.58 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 8.96 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 18.11 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 19.65 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 28.55 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 34.35 k ops/s | - |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 47.37 k ops/s | - |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 64.51 k ops/s | - |

### WebFlux Adapter Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2.84 MiB/op | - | 1.57 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2.82 MiB/op | - | 5.26 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 742.58 KiB/op | - | 5.58 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 737.23 KiB/op | - | 19.65 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 435.8 KiB/op | - | 8.96 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 434 KiB/op | - | 34.35 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 165.09 KiB/op | - | 18.11 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 162.48 KiB/op | - | 47.37 k ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 79.81 KiB/op | - | 28.55 k ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 78.07 KiB/op | - | 110.84 k ops/s |

## Primary Framework E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickE2E`
- **JMH Config**: warmup=1x2s, measurement=2x3s, fork=1, threads=1,4, modes=thrpt, profilers=gc
- **Formal Regression Source**: no
- **Source Row Count**: 16
- **Parsed Row Count**: 16

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-1-framework-e2e.json`
  - Last Modified: 2026-07-22T10:15:10.454Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-4-framework-e2e.json`
  - Last Modified: 2026-07-22T10:16:20.083Z

| Suite | Benchmark | Threads | Mode | Score | Error | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|-------------------|
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 1 | thrpt | 686.87 k ops/s | - | 2.21 KiB/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 4 | thrpt | 675.93 k ops/s | - | 2.21 KiB/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 1 | thrpt | 663.67 k ops/s | - | 2.61 KiB/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 4 | thrpt | 679.13 k ops/s | - | 2.65 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 302.78 k ops/s | - | 12.08 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 224.32 k ops/s | - | 12.09 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 1 | thrpt | 91.3 k ops/s | - | 3.86 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 4 | thrpt | 158.98 k ops/s | - | 3.86 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 208.21 k ops/s | - | 13.18 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 143 k ops/s | - | 13.22 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 1 | thrpt | 81.13 k ops/s | - | 4.58 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 4 | thrpt | 133.95 k ops/s | - | 4.51 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 249.84 k ops/s | - | 13 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 167.47 k ops/s | - | 13.03 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 1 | thrpt | 91.97 k ops/s | - | 4.8 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 4 | thrpt | 147.17 k ops/s | - | 4.79 KiB/op |

## Infrastructure E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E`
- **JMH Config**: warmup=1x2s, measurement=2x3s, fork=1, threads=1,4, modes=thrpt, profilers=gc
- **Formal Regression Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-1-infrastructure-e2e.json`
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-4-infrastructure-e2e.json`

Status: unavailable. Result files were not present. Run benchmarkQuickInfrastructureE2E to include this optional group.

## Component Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickComponent`
- **JMH Config**: warmup=1x2s, measurement=2x3s, fork=1, threads=1, modes=thrpt, profilers=gc
- **Formal Regression Source**: no
- **Source Row Count**: 27
- **Parsed Row Count**: 27

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-1-component.json`
  - Last Modified: 2026-07-22T10:20:12.139Z

| Suite | Benchmark | Threads | Mode | Score | Error | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|-------------------|
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 1 | thrpt | 1.18 M ops/s | - | 5.05 KiB/op |
| Component | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=10) | 1 | thrpt | 3.32 M ops/s | - | 1.71 KiB/op |
| Component | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | 1 | thrpt | 74.47 k ops/s | - | 55.3 KiB/op |
| Component | AggregateRepositoryLoadComponentBenchmark.loadEmptyStateAggregate | 1 | thrpt | 6 M ops/s | - | 1.3 KiB/op |
| Component | AggregateRepositoryLoadComponentBenchmark.loadSnapshot | 1 | thrpt | 1.05 M ops/s | - | 5.14 KiB/op |
| Component | CommandDispatcherChainComponentBenchmark.dispatchSingleHotAggregateThroughChain (handlerCost=NOOP, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 5.94 M ops/s | - | 624 B/op |
| Component | CommandDispatcherChainComponentBenchmark.dispatchSingleHotAggregateThroughChain (handlerCost=NOOP, schedulerStrategy=PARALLEL) | 1 | thrpt | 120.04 k ops/s | - | 710.21 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 1 | thrpt | 16.58 M ops/s | - | 272 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 1 | thrpt | 5.04 M ops/s | - | 1.05 KiB/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 1 | thrpt | 668.85 M ops/s | - | 2.7e-07 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 1 | thrpt | 455.68 k ops/s | - | 10.27 KiB/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 1 | thrpt | 574.93 k ops/s | - | 8.66 KiB/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 1 | thrpt | 684.28 k ops/s | - | 7.34 KiB/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 1 | thrpt | 564.96 k ops/s | - | 8.63 KiB/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 1 | thrpt | 769.14 k ops/s | - | 6.98 KiB/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 1 | thrpt | 859.76 k ops/s | - | 5.89 KiB/op |
| Component | CommandValidationComponentBenchmark.validateCommandBody | 1 | thrpt | 7.81 M ops/s | - | 360.04 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 1 | thrpt | 20.46 M ops/s | - | 168 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryNewAggregateEventStream | 1 | thrpt | 14.29 M ops/s | - | 467.89 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 1 | thrpt | 2.73 G ops/s | - | 6.7e-08 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 1 | thrpt | 20.67 M ops/s | - | 192.48 B/op |
| Component | MongoDocumentComponentBenchmark.eventStreamToDocument | 1 | thrpt | 1.27 M ops/s | - | 4.23 KiB/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 1 | thrpt | 607.53 k ops/s | - | 5.52 KiB/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 1 | thrpt | 450.57 k ops/s | - | 7.73 KiB/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 1 | thrpt | 3.31 M ops/s | - | 1.46 KiB/op |
| Component | WaitNotifyComponentBenchmark.registerWaitRegistration | 1 | thrpt | 31.03 M ops/s | - | 320 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 1 | thrpt | 3.01 M ops/s | - | 1.72 KiB/op |

## WebFlux Adapter Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickWebFlux`
- **JMH Config**: warmup=0, measurement=1x2s, fork=1, threads=1,4, modes=thrpt, profilers=gc
- **Formal Regression Source**: no
- **Source Row Count**: 30
- **Parsed Row Count**: 30

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/webflux/threads-1-webflux.json`
  - Last Modified: 2026-07-22T10:20:53.075Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/webflux/threads-4-webflux.json`
  - Last Modified: 2026-07-22T10:21:34.251Z

| Suite | Benchmark | Threads | Mode | Score | Error | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|-------------------|
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 1 | thrpt | 28.55 k ops/s | - | 79.81 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 4 | thrpt | 110.84 k ops/s | - | 78.07 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 1 | thrpt | 18.11 k ops/s | - | 165.09 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 4 | thrpt | 47.37 k ops/s | - | 162.48 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 1 | thrpt | 5.58 k ops/s | - | 742.58 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 4 | thrpt | 19.65 k ops/s | - | 737.23 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=1, traceWindowSize=10) | 1 | thrpt | 803.6 k ops/s | - | 3.23 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=1, traceWindowSize=10) | 4 | thrpt | 2.34 M ops/s | - | 3.19 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 1 | thrpt | 64.51 k ops/s | - | 36.45 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 4 | thrpt | 260.45 k ops/s | - | 35.85 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1 | thrpt | 1.57 k ops/s | - | 2.84 MiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 4 | thrpt | 5.26 k ops/s | - | 2.82 MiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=1, traceWindowSize=10) | 1 | thrpt | 747.65 k ops/s | - | 3.76 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=1, traceWindowSize=10) | 4 | thrpt | 1.91 M ops/s | - | 3.72 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=10, traceWindowSize=10) | 1 | thrpt | 68.1 k ops/s | - | 36.89 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=10, traceWindowSize=10) | 4 | thrpt | 216.52 k ops/s | - | 36.38 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 1 | thrpt | 8.96 k ops/s | - | 435.8 KiB/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 4 | thrpt | 34.35 k ops/s | - | 434 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.commandResultJsonServerResponseOnly | 1 | thrpt | 831.41 k ops/s | - | 4.39 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.commandResultJsonServerResponseOnly | 4 | thrpt | 2.26 M ops/s | - | 4.36 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.extractPreparedCommandMessage | 1 | thrpt | 3.57 M ops/s | - | 1.35 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.extractPreparedCommandMessage | 4 | thrpt | 6.74 M ops/s | - | 1.33 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.handlePreparedAddCartItemRequestWaitSent | 1 | thrpt | 118.69 k ops/s | - | 11.08 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.handlePreparedAddCartItemRequestWaitSent | 4 | thrpt | 234.42 k ops/s | - | 10.96 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.sendWaitSentCoreFromExtractedMessage | 1 | thrpt | 1.05 M ops/s | - | 3.45 KiB/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.sendWaitSentCoreFromExtractedMessage | 4 | thrpt | 1.01 M ops/s | - | 3.46 KiB/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseServerResponseOnly | 1 | thrpt | 1.84 M ops/s | - | 3.33 KiB/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseServerResponseOnly | 4 | thrpt | 7.13 M ops/s | - | 3.21 KiB/op |
| WebFlux Adapter | WebFluxResponseBenchmark.fluxJsonStreamingArrayServerResponseOnly | 1 | thrpt | 5.2 M ops/s | - | 1.05 KiB/op |
| WebFlux Adapter | WebFluxResponseBenchmark.fluxJsonStreamingArrayServerResponseOnly | 4 | thrpt | 19.6 M ops/s | - | 1.04 KiB/op |

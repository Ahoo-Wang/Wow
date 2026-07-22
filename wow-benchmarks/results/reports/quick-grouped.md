# Quick Grouped Benchmark Report

## Policy
- Quick results are directional feedback; run Baseline E2E before updating baselines or claiming framework performance conclusions.
- Framework E2E results isolate command pipeline overhead with in-memory or noop stores; they are not production persistence capacity.
- Infrastructure E2E results reflect real Redis or Mongo persistence paths when services are available.
- No-snapshot growing-stream scenarios are diagnostics for replay pressure, not default E2E goals.
- Component results explain bottlenecks and are not standalone performance goals.
- Smoke results are excluded from performance reports.

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
- **Generated At**: 2026-07-22T18:21:34+08:00
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

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 81134.42 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 91298.61 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 91971.23 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 133949.08 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 143004.97 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 147171.40 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 158977.44 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 167471.78 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 208208.96 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 224318.46 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13542.0 B/op | - | 143004.97 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13495.1 B/op | - | 208208.96 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13338.4 B/op | - | 167471.78 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13312.9 B/op | - | 249836.37 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12375.3 B/op | - | 224318.46 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12371.7 B/op | - | 302779.76 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4914.0 B/op | - | 91971.23 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4904.8 B/op | - | 147171.40 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4691.7 B/op | - | 81134.42 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4616.4 B/op | - | 133949.08 | ops/s |

## Component Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | 74469.55 | - | ops/s |
| Component | 1 | CommandDispatcherChainComponentBenchmark.dispatchSingleHotAggregateThroughChain (handlerCost=NOOP, schedulerStrategy=PARALLEL) | 120042.09 | - | ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 450569.05 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 455681.50 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 564959.75 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 574927.18 | - | ops/s |
| Component | 1 | SerializationComponentBenchmark.commandSerializeDeserialize | 607526.58 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 684281.24 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateOnly | 769137.27 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 859762.08 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | thrpt | 56627.9 B/op | - | 74469.55 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10520.0 B/op | - | 455681.50 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | thrpt | 8872.0 B/op | - | 574927.18 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | thrpt | 8840.0 B/op | - | 564959.75 | ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | thrpt | 7920.0 B/op | - | 450569.05 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | thrpt | 7512.0 B/op | - | 684281.24 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateOnly | thrpt | 7144.0 B/op | - | 769137.27 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | thrpt | 6032.0 B/op | - | 859762.08 | ops/s |
| Component | 1 | SerializationComponentBenchmark.commandSerializeDeserialize | thrpt | 5648.0 B/op | - | 607526.58 | ops/s |
| Component | 1 | AggregateRepositoryLoadComponentBenchmark.loadSnapshot | thrpt | 5264.0 B/op | - | 1050689.18 | ops/s |

## WebFlux Adapter Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1573.91 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 5259.54 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 5576.25 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 8963.46 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 18106.25 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 19647.09 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 28548.24 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 34350.88 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 47369.89 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 64511.66 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2982851.6 B/op | - | 1573.91 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2960991.6 B/op | - | 5259.54 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 760399.9 B/op | - | 5576.25 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 754925.9 B/op | - | 19647.09 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 446260.1 B/op | - | 8963.46 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 444420.0 B/op | - | 34350.88 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 169049.7 B/op | - | 18106.25 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 166383.0 B/op | - | 47369.89 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 81728.4 B/op | - | 28548.24 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 79940.0 B/op | - | 110843.89 | ops/s |

## Group Details

### Primary Framework E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 81134.42 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 91298.61 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 91971.23 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 133949.08 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 143004.97 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 147171.40 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 158977.44 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 167471.78 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 208208.96 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 224318.46 | - | ops/s |

### Primary Framework E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13542.0 B/op | - | 143004.97 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13495.1 B/op | - | 208208.96 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13338.4 B/op | - | 167471.78 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13312.9 B/op | - | 249836.37 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12375.3 B/op | - | 224318.46 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12371.7 B/op | - | 302779.76 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4914.0 B/op | - | 91971.23 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4904.8 B/op | - | 147171.40 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4691.7 B/op | - | 81134.42 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4616.4 B/op | - | 133949.08 | ops/s |

### Component Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | 74469.55 | - | ops/s |
| Component | 1 | CommandDispatcherChainComponentBenchmark.dispatchSingleHotAggregateThroughChain (handlerCost=NOOP, schedulerStrategy=PARALLEL) | 120042.09 | - | ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 450569.05 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 455681.50 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 564959.75 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 574927.18 | - | ops/s |
| Component | 1 | SerializationComponentBenchmark.commandSerializeDeserialize | 607526.58 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 684281.24 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateOnly | 769137.27 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 859762.08 | - | ops/s |

### Component Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | thrpt | 56627.9 B/op | - | 74469.55 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10520.0 B/op | - | 455681.50 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | thrpt | 8872.0 B/op | - | 574927.18 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | thrpt | 8840.0 B/op | - | 564959.75 | ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | thrpt | 7920.0 B/op | - | 450569.05 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | thrpt | 7512.0 B/op | - | 684281.24 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateOnly | thrpt | 7144.0 B/op | - | 769137.27 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | thrpt | 6032.0 B/op | - | 859762.08 | ops/s |
| Component | 1 | SerializationComponentBenchmark.commandSerializeDeserialize | thrpt | 5648.0 B/op | - | 607526.58 | ops/s |
| Component | 1 | AggregateRepositoryLoadComponentBenchmark.loadSnapshot | thrpt | 5264.0 B/op | - | 1050689.18 | ops/s |

### WebFlux Adapter Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1573.91 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 5259.54 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 5576.25 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 8963.46 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 18106.25 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 19647.09 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 28548.24 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 34350.88 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 47369.89 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 64511.66 | - | ops/s |

### WebFlux Adapter Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2982851.6 B/op | - | 1573.91 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2960991.6 B/op | - | 5259.54 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 760399.9 B/op | - | 5576.25 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 754925.9 B/op | - | 19647.09 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 446260.1 B/op | - | 8963.46 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 444420.0 B/op | - | 34350.88 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 169049.7 B/op | - | 18106.25 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 166383.0 B/op | - | 47369.89 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 81728.4 B/op | - | 28548.24 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 79940.0 B/op | - | 110843.89 | ops/s |

## Primary Framework E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickE2E`
- **JMH Config**: warmup=1x2s, measurement=2x3s, fork=1, threads=1,4, modes=thrpt, profilers=gc
- **Performance Conclusion Source**: no
- **Source Row Count**: 16
- **Parsed Row Count**: 16

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-1-framework-e2e.json`
  - Last Modified: 2026-07-22T10:15:10.454Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-4-framework-e2e.json`
  - Last Modified: 2026-07-22T10:16:20.083Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 1 | thrpt | 686869.48 | - | ops/s | 2260.2 B/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 4 | thrpt | 675928.89 | - | ops/s | 2261.6 B/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 1 | thrpt | 663667.93 | - | ops/s | 2668.2 B/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 4 | thrpt | 679130.82 | - | ops/s | 2718.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 302779.76 | - | ops/s | 12371.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 224318.46 | - | ops/s | 12375.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 1 | thrpt | 91298.61 | - | ops/s | 3953.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 4 | thrpt | 158977.44 | - | ops/s | 3956.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 208208.96 | - | ops/s | 13495.1 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 143004.97 | - | ops/s | 13542.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 1 | thrpt | 81134.42 | - | ops/s | 4691.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 4 | thrpt | 133949.08 | - | ops/s | 4616.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 249836.37 | - | ops/s | 13312.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 167471.78 | - | ops/s | 13338.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 1 | thrpt | 91971.23 | - | ops/s | 4914.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 4 | thrpt | 147171.40 | - | ops/s | 4904.8 B/op |

## Infrastructure E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E`
- **JMH Config**: warmup=1x2s, measurement=2x3s, fork=1, threads=1,4, modes=thrpt, profilers=gc
- **Performance Conclusion Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-1-infrastructure-e2e.json`
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-4-infrastructure-e2e.json`

Status: unavailable. Result files were not present. Run benchmarkQuickInfrastructureE2E to include this optional group.

## Component Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickComponent`
- **JMH Config**: warmup=1x2s, measurement=2x3s, fork=1, threads=1, modes=thrpt, profilers=gc
- **Performance Conclusion Source**: no
- **Source Row Count**: 27
- **Parsed Row Count**: 27

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-1-component.json`
  - Last Modified: 2026-07-22T10:20:12.139Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 1 | thrpt | 1178260.56 | - | ops/s | 5176.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=10) | 1 | thrpt | 3315394.59 | - | ops/s | 1752.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents (eventCount=500) | 1 | thrpt | 74469.55 | - | ops/s | 56627.9 B/op |
| Component | AggregateRepositoryLoadComponentBenchmark.loadEmptyStateAggregate | 1 | thrpt | 6002517.64 | - | ops/s | 1328.0 B/op |
| Component | AggregateRepositoryLoadComponentBenchmark.loadSnapshot | 1 | thrpt | 1050689.18 | - | ops/s | 5264.0 B/op |
| Component | CommandDispatcherChainComponentBenchmark.dispatchSingleHotAggregateThroughChain (handlerCost=NOOP, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 5940060.46 | - | ops/s | 624.0 B/op |
| Component | CommandDispatcherChainComponentBenchmark.dispatchSingleHotAggregateThroughChain (handlerCost=NOOP, schedulerStrategy=PARALLEL) | 1 | thrpt | 120042.09 | - | ops/s | 710.2 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 1 | thrpt | 16575792.59 | - | ops/s | 272.0 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 1 | thrpt | 5039987.87 | - | ops/s | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 1 | thrpt | 668849367.69 | - | ops/s | 0.0 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 1 | thrpt | 455681.50 | - | ops/s | 10520.0 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 1 | thrpt | 574927.18 | - | ops/s | 8872.0 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 1 | thrpt | 684281.24 | - | ops/s | 7512.0 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 1 | thrpt | 564959.75 | - | ops/s | 8840.0 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 1 | thrpt | 769137.27 | - | ops/s | 7144.0 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 1 | thrpt | 859762.08 | - | ops/s | 6032.0 B/op |
| Component | CommandValidationComponentBenchmark.validateCommandBody | 1 | thrpt | 7806847.66 | - | ops/s | 360.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 1 | thrpt | 20455092.36 | - | ops/s | 168.0 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryNewAggregateEventStream | 1 | thrpt | 14294165.52 | - | ops/s | 467.9 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 1 | thrpt | 2731742591.68 | - | ops/s | 0.0 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 1 | thrpt | 20673244.39 | - | ops/s | 192.5 B/op |
| Component | MongoDocumentComponentBenchmark.eventStreamToDocument | 1 | thrpt | 1266511.38 | - | ops/s | 4336.0 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 1 | thrpt | 607526.58 | - | ops/s | 5648.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 1 | thrpt | 450569.05 | - | ops/s | 7920.0 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 1 | thrpt | 3308915.46 | - | ops/s | 1496.0 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitRegistration | 1 | thrpt | 31033100.12 | - | ops/s | 320.0 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 1 | thrpt | 3007627.70 | - | ops/s | 1760.0 B/op |

## WebFlux Adapter Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickWebFlux`
- **JMH Config**: warmup=0, measurement=1x2s, fork=1, threads=1,4, modes=thrpt, profilers=gc
- **Performance Conclusion Source**: no
- **Source Row Count**: 30
- **Parsed Row Count**: 30

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/webflux/threads-1-webflux.json`
  - Last Modified: 2026-07-22T10:20:53.075Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/webflux/threads-4-webflux.json`
  - Last Modified: 2026-07-22T10:21:34.251Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 1 | thrpt | 28548.24 | - | ops/s | 81728.4 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 4 | thrpt | 110843.89 | - | ops/s | 79940.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 1 | thrpt | 18106.25 | - | ops/s | 169049.7 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 4 | thrpt | 47369.89 | - | ops/s | 166383.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 1 | thrpt | 5576.25 | - | ops/s | 760399.9 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 4 | thrpt | 19647.09 | - | ops/s | 754925.9 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=1, traceWindowSize=10) | 1 | thrpt | 803604.02 | - | ops/s | 3310.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=1, traceWindowSize=10) | 4 | thrpt | 2335131.47 | - | ops/s | 3268.2 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 1 | thrpt | 64511.66 | - | ops/s | 37323.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 4 | thrpt | 260452.97 | - | ops/s | 36714.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1 | thrpt | 1573.91 | - | ops/s | 2982851.6 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 4 | thrpt | 5259.54 | - | ops/s | 2960991.6 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=1, traceWindowSize=10) | 1 | thrpt | 747650.70 | - | ops/s | 3851.1 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=1, traceWindowSize=10) | 4 | thrpt | 1906565.36 | - | ops/s | 3809.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=10, traceWindowSize=10) | 1 | thrpt | 68097.86 | - | ops/s | 37772.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=10, traceWindowSize=10) | 4 | thrpt | 216517.36 | - | ops/s | 37251.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 1 | thrpt | 8963.46 | - | ops/s | 446260.1 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 4 | thrpt | 34350.88 | - | ops/s | 444420.0 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.commandResultJsonServerResponseOnly | 1 | thrpt | 831407.85 | - | ops/s | 4498.1 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.commandResultJsonServerResponseOnly | 4 | thrpt | 2264673.75 | - | ops/s | 4459.9 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.extractPreparedCommandMessage | 1 | thrpt | 3573198.45 | - | ops/s | 1382.2 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.extractPreparedCommandMessage | 4 | thrpt | 6743832.19 | - | ops/s | 1359.3 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.handlePreparedAddCartItemRequestWaitSent | 1 | thrpt | 118693.32 | - | ops/s | 11346.1 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.handlePreparedAddCartItemRequestWaitSent | 4 | thrpt | 234418.11 | - | ops/s | 11223.6 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.sendWaitSentCoreFromExtractedMessage | 1 | thrpt | 1052901.94 | - | ops/s | 3535.8 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.sendWaitSentCoreFromExtractedMessage | 4 | thrpt | 1012879.97 | - | ops/s | 3541.8 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseServerResponseOnly | 1 | thrpt | 1835774.35 | - | ops/s | 3408.4 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseServerResponseOnly | 4 | thrpt | 7125659.23 | - | ops/s | 3290.0 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.fluxJsonStreamingArrayServerResponseOnly | 1 | thrpt | 5198585.40 | - | ops/s | 1075.5 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.fluxJsonStreamingArrayServerResponseOnly | 4 | thrpt | 19596863.45 | - | ops/s | 1067.4 B/op |


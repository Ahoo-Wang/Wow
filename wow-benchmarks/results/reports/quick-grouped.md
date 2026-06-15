# Quick Grouped Benchmark Report

## Policy
- Quick results are directional feedback; run Full E2E before updating baselines or claiming framework performance conclusions.
- Framework E2E results isolate command pipeline overhead with in-memory or noop stores; they are not production persistence capacity.
- Infrastructure E2E results reflect real Redis or Mongo persistence paths when services are available.
- No-snapshot growing-stream scenarios are diagnostics for replay pressure, not default E2E goals.
- Component results explain bottlenecks and are not standalone performance goals.
- Smoke results are excluded from performance reports.

## Environment
- **Version**: 8.5.0
- **JVM**: OpenJDK 64-Bit Server VM 17.0.7+7-LTS
- **OS**: Mac OS X 26.5.1 aarch64
- **DateTime**: 2026-06-16T02:10:59+08:00
- **CPU Cores**: 14
- **Physical Memory**: 24.0 GiB
- **Benchmark JVM Args**: `-Xmx4g -Xms4g -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+AlwaysPreTouch`

## Run Profiles

- **Primary Framework E2E**: warmup=1x3s, measurement=2x5s, fork=1, threads=1,4, modes=thrpt,avgt, profilers=gc,async
- **Infrastructure E2E**: warmup=1x3s, measurement=2x5s, fork=1, threads=1,4, modes=thrpt,avgt, profilers=gc,async
- **Component**: warmup=1x3s, measurement=2x5s, fork=1, threads=1,4, modes=thrpt,avgt, profilers=gc,async
- **WebFlux Adapter**: warmup=0, measurement=1x2s, fork=1, threads=1,4, modes=thrpt, profilers=gc

## Framework E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 89604.72 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 93219.61 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 94495.85 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 153677.95 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 162579.58 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 166292.40 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1301696.42 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1328551.51 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1379683.86 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1380406.44 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5555.9 B/op | - | 162579.58 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5481.2 B/op | - | 94495.85 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5317.7 B/op | - | 153677.95 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5245.4 B/op | - | 89604.72 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3959.3 B/op | - | 166292.40 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3954.1 B/op | - | 93219.61 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3261.8 B/op | - | 1380406.44 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3260.2 B/op | - | 1301696.42 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3022.6 B/op | - | 1696386.12 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3020.2 B/op | - | 1379683.86 | ops/s |

## Component Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 21626.28 | - | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 63118.87 | - | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 244319.96 | - | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 348069.46 | - | ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 493449.38 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 518491.66 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 533800.24 | - | ops/s |
| Component | 1 | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 538468.87 | - | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 542689.85 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 632589.89 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062584.0 B/op | - | 63118.87 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062584.0 B/op | - | 21626.28 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52984.0 B/op | - | 774457.21 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52984.0 B/op | - | 244319.96 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16816.0 B/op | - | 1855035.25 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16816.0 B/op | - | 542689.85 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10528.1 B/op | - | 1546409.02 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10320.1 B/op | - | 533800.24 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | thrpt | 8680.1 B/op | - | 664880.57 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | thrpt | 8672.1 B/op | - | 1890714.16 | ops/s |

## Infrastructure E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 5142.71 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 6564.18 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 14504.56 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 21059.85 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 14330.4 B/op | - | 5142.71 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 13563.6 B/op | - | 14504.56 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 6345.6 B/op | - | 6564.18 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 5630.6 B/op | - | 21059.85 | ops/s |

## WebFlux Adapter Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1646.23 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 5862.60 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 5915.28 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 9276.88 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 19804.86 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 21547.19 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 33709.86 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 37108.99 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 55026.52 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 67205.76 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2979595.6 B/op | - | 1646.23 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2962155.2 B/op | - | 5862.60 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 759238.1 B/op | - | 5915.28 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 752725.4 B/op | - | 21547.19 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 445767.7 B/op | - | 9276.88 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 444069.1 B/op | - | 37108.99 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 168622.5 B/op | - | 19804.86 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 167567.7 B/op | - | 55026.52 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 81189.6 B/op | - | 33709.86 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 78536.8 B/op | - | 114395.88 | ops/s |

## Group Details

### Primary Framework E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 89604.72 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 93219.61 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 94495.85 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 153677.95 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 162579.58 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 166292.40 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1301696.42 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1328551.51 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1379683.86 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1380406.44 | - | ops/s |

### Primary Framework E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5555.9 B/op | - | 162579.58 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5481.2 B/op | - | 94495.85 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5317.7 B/op | - | 153677.95 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5245.4 B/op | - | 89604.72 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3959.3 B/op | - | 166292.40 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3954.1 B/op | - | 93219.61 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3261.8 B/op | - | 1380406.44 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3260.2 B/op | - | 1301696.42 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3022.6 B/op | - | 1696386.12 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3020.2 B/op | - | 1379683.86 | ops/s |

### Infrastructure E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 5142.71 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 6564.18 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 14504.56 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 21059.85 | - | ops/s |

### Infrastructure E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 14330.4 B/op | - | 5142.71 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 13563.6 B/op | - | 14504.56 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 6345.6 B/op | - | 6564.18 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 5630.6 B/op | - | 21059.85 | ops/s |

### Component Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 21626.28 | - | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 63118.87 | - | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 244319.96 | - | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 348069.46 | - | ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 493449.38 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 518491.66 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 533800.24 | - | ops/s |
| Component | 1 | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 538468.87 | - | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 542689.85 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 632589.89 | - | ops/s |

### Component Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062584.0 B/op | - | 63118.87 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062584.0 B/op | - | 21626.28 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52984.0 B/op | - | 774457.21 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52984.0 B/op | - | 244319.96 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16816.0 B/op | - | 1855035.25 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16816.0 B/op | - | 542689.85 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10528.1 B/op | - | 1546409.02 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10320.1 B/op | - | 533800.24 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | thrpt | 8680.1 B/op | - | 664880.57 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | thrpt | 8672.1 B/op | - | 1890714.16 | ops/s |

### WebFlux Adapter Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1646.23 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 5862.60 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 5915.28 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 9276.88 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 19804.86 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 21547.19 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 33709.86 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 37108.99 | - | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 55026.52 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 67205.76 | - | ops/s |

### WebFlux Adapter Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2979595.6 B/op | - | 1646.23 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2962155.2 B/op | - | 5862.60 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 759238.1 B/op | - | 5915.28 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 752725.4 B/op | - | 21547.19 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 445767.7 B/op | - | 9276.88 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 444069.1 B/op | - | 37108.99 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 168622.5 B/op | - | 19804.86 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | thrpt | 167567.7 B/op | - | 55026.52 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 81189.6 B/op | - | 33709.86 | ops/s |
| WebFlux Adapter | 4 | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | thrpt | 78536.8 B/op | - | 114395.88 | ops/s |

## Primary Framework E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickE2E`
- **JMH Config**: warmup=1x3s, measurement=2x5s, fork=1, threads=1,4, modes=thrpt,avgt, profilers=gc,async
- **Performance Conclusion Source**: no
- **Source Row Count**: 24
- **Parsed Row Count**: 24

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-1-framework-e2e.json`
  - Last Modified: 2026-06-15T17:03:33.404Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-4-framework-e2e.json`
  - Last Modified: 2026-06-15T17:06:24.500Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | avgt | 10.27 | - | us/op | 3953.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | thrpt | 93219.61 | - | ops/s | 3954.1 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 4 | avgt | 24.07 | - | us/op | 3958.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 4 | thrpt | 166292.40 | - | ops/s | 3959.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | avgt | 11.12 | - | us/op | 5245.1 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | thrpt | 89604.72 | - | ops/s | 5245.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 4 | avgt | 25.65 | - | us/op | 5269.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 4 | thrpt | 153677.95 | - | ops/s | 5317.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | avgt | 10.68 | - | us/op | 5537.8 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | thrpt | 94495.85 | - | ops/s | 5481.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 4 | avgt | 24.32 | - | us/op | 5555.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 4 | thrpt | 162579.58 | - | ops/s | 5555.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | avgt | 632.19 | - | ns/op | 2252.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | thrpt | 1636642.07 | - | ops/s | 2252.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 4 | avgt | 3.32 | - | us/op | 2253.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 4 | thrpt | 1328551.51 | - | ops/s | 2269.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | avgt | 727.31 | - | ns/op | 3020.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | thrpt | 1379683.86 | - | ops/s | 3020.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 4 | avgt | 2.62 | - | us/op | 3086.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 4 | thrpt | 1696386.12 | - | ops/s | 3022.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | avgt | 785.21 | - | ns/op | 3260.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | thrpt | 1301696.42 | - | ops/s | 3260.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 4 | avgt | 3.03 | - | us/op | 3261.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 4 | thrpt | 1380406.44 | - | ops/s | 3261.8 B/op |

## Infrastructure E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E`
- **JMH Config**: warmup=1x3s, measurement=2x5s, fork=1, threads=1,4, modes=thrpt,avgt, profilers=gc,async
- **Performance Conclusion Source**: no
- **Source Row Count**: 8
- **Parsed Row Count**: 8

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-1-infrastructure-e2e.json`
  - Last Modified: 2026-06-15T17:07:25.332Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-4-infrastructure-e2e.json`
  - Last Modified: 2026-06-15T17:08:26.428Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | avgt | 194.36 | - | us/op | 14387.3 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | thrpt | 5142.71 | - | ops/s | 14330.4 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | avgt | 274.62 | - | us/op | 13514.1 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | thrpt | 14504.56 | - | ops/s | 13563.6 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | avgt | 153.05 | - | us/op | 6397.4 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | thrpt | 6564.18 | - | ops/s | 6345.6 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | avgt | 190.32 | - | us/op | 5527.7 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | thrpt | 21059.85 | - | ops/s | 5630.6 B/op |

## Component Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickComponent`
- **JMH Config**: warmup=1x3s, measurement=2x5s, fork=1, threads=1,4, modes=thrpt,avgt, profilers=gc,async
- **Performance Conclusion Source**: no
- **Source Row Count**: 264
- **Parsed Row Count**: 264

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-1-component.json`
  - Last Modified: 2026-06-15T17:39:01.202Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-4-component.json`
  - Last Modified: 2026-06-15T18:09:35.633Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Component | AccessorComponentBenchmark.constructorInvoke0 | 1 | avgt | 3.27 | - | ns/op | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke0 | 1 | thrpt | 301917295.44 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke0 | 4 | avgt | 3.37 | - | ns/op | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke0 | 4 | thrpt | 1187766734.50 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 1 | avgt | 3.43 | - | ns/op | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 1 | thrpt | 291757173.92 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 4 | avgt | 3.90 | - | ns/op | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 4 | thrpt | 1026008770.82 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 1 | avgt | 3.63 | - | ns/op | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 1 | thrpt | 274380494.62 | - | ops/s | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 4 | avgt | 4.14 | - | ns/op | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 4 | thrpt | 938337314.08 | - | ops/s | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 1 | avgt | 5.07 | - | ns/op | 40.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 1 | thrpt | 197148058.47 | - | ops/s | 40.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 4 | avgt | 5.54 | - | ns/op | 40.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 4 | thrpt | 701713616.20 | - | ops/s | 40.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 1 | avgt | 8.52 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 1 | thrpt | 116910080.58 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 4 | avgt | 9.42 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 4 | thrpt | 410766545.40 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 1 | avgt | 7.48 | - | ns/op | 56.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 1 | thrpt | 134880465.51 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 4 | avgt | 8.14 | - | ns/op | 56.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 4 | thrpt | 463693359.45 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 1 | avgt | 8.31 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 1 | thrpt | 120397428.10 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 4 | avgt | 9.12 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 4 | thrpt | 417909040.64 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 1 | avgt | 7.40 | - | ns/op | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 1 | thrpt | 135685746.20 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 4 | avgt | 8.04 | - | ns/op | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 4 | thrpt | 476447786.95 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 1 | avgt | 7.97 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 1 | thrpt | 125125840.76 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 4 | avgt | 8.68 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 4 | thrpt | 440608546.73 | - | ops/s | 80.0 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 1 | avgt | 261.14 | - | ns/op | 1840.0 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 1 | thrpt | 3866557.97 | - | ops/s | 1840.0 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 4 | avgt | 2.21 | - | us/op | 1840.0 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 4 | thrpt | 1985901.92 | - | ops/s | 1840.0 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 1 | avgt | 113.26 | - | ns/op | 904.0 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 1 | thrpt | 8832326.07 | - | ops/s | 904.0 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 4 | avgt | 684.67 | - | ns/op | 904.0 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 4 | thrpt | 6230989.03 | - | ops/s | 904.0 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 1 | avgt | 454.22 | - | ns/op | 2912.0 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 1 | thrpt | 2199817.73 | - | ops/s | 2912.0 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 4 | avgt | 2.97 | - | us/op | 2912.0 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 4 | thrpt | 1346141.45 | - | ops/s | 2912.0 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 1 | avgt | 829.82 | - | ns/op | 5176.0 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 1 | thrpt | 1196883.48 | - | ops/s | 5176.0 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 4 | avgt | 3.23 | - | us/op | 5224.0 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 4 | thrpt | 1235176.87 | - | ops/s | 5232.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 1 | avgt | 145.05 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 1 | thrpt | 6875493.66 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 4 | avgt | 152.70 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 4 | thrpt | 18510135.99 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 1 | avgt | 146.63 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 1 | thrpt | 6817816.42 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 4 | avgt | 152.27 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 4 | thrpt | 19473652.16 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 1 | avgt | 146.19 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 1 | thrpt | 6882314.98 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 4 | avgt | 151.92 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 4 | thrpt | 19302439.15 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 1 | avgt | 145.16 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 1 | thrpt | 6870428.81 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 4 | avgt | 154.27 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 4 | thrpt | 18989790.45 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 1 | avgt | 945.25 | - | ns/op | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 1 | thrpt | 1083327.94 | - | ops/s | 5128.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 4 | avgt | 1.63 | - | us/op | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 4 | thrpt | 2194861.47 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 1 | avgt | 914.91 | - | ns/op | 5128.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 1 | thrpt | 1079201.95 | - | ops/s | 5128.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 4 | avgt | 1.54 | - | us/op | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 4 | thrpt | 2339218.69 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 1 | avgt | 924.65 | - | ns/op | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 1 | thrpt | 1084021.31 | - | ops/s | 5128.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 4 | avgt | 1.57 | - | us/op | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 4 | thrpt | 2389748.47 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 1 | avgt | 922.23 | - | ns/op | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 1 | thrpt | 1095078.24 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 4 | avgt | 1.64 | - | us/op | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 4 | thrpt | 2413997.48 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 1 | avgt | 361.14 | - | ns/op | 2256.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 1 | thrpt | 2873625.19 | - | ops/s | 2256.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 4 | avgt | 380.76 | - | ns/op | 2256.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 4 | thrpt | 9363445.08 | - | ops/s | 2256.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 1 | avgt | 4.22 | - | us/op | 52984.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 1 | thrpt | 244319.96 | - | ops/s | 52984.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 4 | avgt | 4.54 | - | us/op | 53000.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 4 | thrpt | 774457.21 | - | ops/s | 52984.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 1 | avgt | 1.83 | - | us/op | 16816.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 1 | thrpt | 542689.85 | - | ops/s | 16816.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 4 | avgt | 1.86 | - | us/op | 16816.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 4 | thrpt | 1855035.25 | - | ops/s | 16816.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 1 | avgt | 46.21 | - | us/op | 1062584.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 1 | thrpt | 21626.28 | - | ops/s | 1062584.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 4 | avgt | 53.24 | - | us/op | 1062584.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 4 | thrpt | 63118.87 | - | ops/s | 1062584.0 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 1 | avgt | 2.12 | - | ns/op | 32.0 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 1 | thrpt | 476803008.13 | - | ops/s | 32.0 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 4 | avgt | 2.39 | - | ns/op | 32.0 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 4 | thrpt | 1467211878.36 | - | ops/s | 32.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 1 | avgt | 47.91 | - | ns/op | 240.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 1 | thrpt | 20648965.60 | - | ops/s | 240.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 4 | avgt | 660.21 | - | ns/op | 240.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 4 | thrpt | 6235708.34 | - | ops/s | 240.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 1 | avgt | 55.27 | - | ns/op | 272.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 1 | thrpt | 17960067.60 | - | ops/s | 272.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 4 | avgt | 702.50 | - | ns/op | 272.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 4 | thrpt | 6301540.67 | - | ops/s | 272.0 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 1 | avgt | 14.35 | - | ns/op | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 1 | thrpt | 69643469.93 | - | ops/s | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 4 | avgt | 17.59 | - | ns/op | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 4 | thrpt | 198715926.98 | - | ops/s | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 1 | avgt | 181.07 | - | ns/op | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 1 | thrpt | 5536133.26 | - | ops/s | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 4 | avgt | 2.03 | - | us/op | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 4 | thrpt | 2012114.51 | - | ops/s | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 1 | avgt | 3.63 | - | ns/op | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 1 | thrpt | 275338104.00 | - | ops/s | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 4 | avgt | 4.04 | - | ns/op | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 4 | thrpt | 1012999926.26 | - | ops/s | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 1 | avgt | 180.54 | - | ns/op | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 1 | thrpt | 5547605.30 | - | ops/s | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 4 | avgt | 2.02 | - | us/op | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 4 | thrpt | 2042431.62 | - | ops/s | 1080.0 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 1 | avgt | 1.89 | - | us/op | 10320.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 1 | thrpt | 533800.24 | - | ops/s | 10320.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 4 | avgt | 2.62 | - | us/op | 10480.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 4 | thrpt | 1546409.02 | - | ops/s | 10528.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 1 | avgt | 1.58 | - | us/op | 8632.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 1 | thrpt | 632589.89 | - | ops/s | 8648.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 4 | avgt | 2.09 | - | us/op | 8728.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 4 | thrpt | 1890714.16 | - | ops/s | 8672.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 1 | avgt | 1.30 | - | us/op | 7448.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 1 | thrpt | 756240.31 | - | ops/s | 7448.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 4 | avgt | 2.64 | - | us/op | 7424.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 4 | thrpt | 1740979.53 | - | ops/s | 7424.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 1 | avgt | 1.55 | - | us/op | 8616.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 1 | thrpt | 664880.57 | - | ops/s | 8680.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 4 | avgt | 2.07 | - | us/op | 8640.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 4 | thrpt | 1937606.25 | - | ops/s | 8648.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 1 | avgt | 1.19 | - | us/op | 7160.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 1 | thrpt | 843583.56 | - | ops/s | 7136.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 4 | avgt | 2.77 | - | us/op | 7152.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 4 | thrpt | 1831558.54 | - | ops/s | 7120.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 1 | avgt | 1.06 | - | us/op | 6032.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 1 | thrpt | 955234.60 | - | ops/s | 6032.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 4 | avgt | 2.63 | - | us/op | 6032.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 4 | thrpt | 1609033.51 | - | ops/s | 6032.1 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 1 | avgt | 1.94 | - | us/op | 6442.7 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 1 | thrpt | 518491.66 | - | ops/s | 6465.7 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 4 | avgt | 11.71 | - | us/op | 6606.2 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 4 | thrpt | 348069.46 | - | ops/s | 6445.0 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 1 | avgt | 220.08 | - | ns/op | 1096.0 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 1 | thrpt | 4732357.12 | - | ops/s | 1096.0 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 4 | avgt | 211.37 | - | ns/op | 1096.0 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 4 | thrpt | 20018024.73 | - | ops/s | 1048.0 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 1 | avgt | 64.30 | - | ns/op | 608.0 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 1 | thrpt | 15307934.16 | - | ops/s | 608.0 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 4 | avgt | 70.77 | - | ns/op | 608.0 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 4 | thrpt | 51604475.45 | - | ops/s | 608.0 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 1 | avgt | 21.45 | - | ns/op | 104.0 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 1 | thrpt | 46413063.64 | - | ops/s | 104.0 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 4 | avgt | 19.79 | - | ns/op | 48.0 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 4 | thrpt | 200520819.62 | - | ops/s | 48.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 1 | avgt | 44.99 | - | ns/op | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 1 | thrpt | 22363547.98 | - | ops/s | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 4 | avgt | 48.27 | - | ns/op | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 4 | thrpt | 82004131.91 | - | ops/s | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 1 | avgt | 48.17 | - | ns/op | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 1 | thrpt | 20533946.23 | - | ops/s | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 4 | avgt | 51.14 | - | ns/op | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 4 | thrpt | 78633202.89 | - | ops/s | 168.0 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 1 | avgt | 430.97 | - | ns/op | 2664.2 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 1 | thrpt | 2303813.35 | - | ops/s | 2664.2 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 4 | avgt | 3.00 | - | us/op | 2639.6 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 4 | thrpt | 1392358.83 | - | ops/s | 2639.8 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 1 | avgt | 362.29 | - | ns/op | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 1 | thrpt | 2745185.66 | - | ops/s | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 4 | avgt | 2.91 | - | us/op | 2136.0 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 4 | thrpt | 1464125.05 | - | ops/s | 2104.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 1 | avgt | 1.95 | - | us/op | 7288.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 1 | thrpt | 538468.87 | - | ops/s | 7288.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 4 | avgt | 2.64 | - | us/op | 7400.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 4 | thrpt | 1430686.30 | - | ops/s | 7432.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 1 | avgt | 61.08 | - | ns/op | 568.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 1 | thrpt | 16365972.37 | - | ops/s | 568.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 4 | avgt | 68.81 | - | ns/op | 568.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 4 | thrpt | 56304154.17 | - | ops/s | 568.0 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 1 | avgt | 362.85 | - | ns/op | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 1 | thrpt | 2717733.15 | - | ops/s | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 4 | avgt | 2.88 | - | us/op | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 4 | thrpt | 1416296.27 | - | ops/s | 2168.0 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 1 | avgt | 435.00 | - | ns/op | 867.7 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 1 | thrpt | 2389540.28 | - | ops/s | 867.3 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 4 | avgt | 725.84 | - | ns/op | 858.8 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 4 | thrpt | 5630388.86 | - | ops/s | 859.8 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 1 | avgt | 65.87 | - | ns/op | 512.0 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 1 | thrpt | 15361651.67 | - | ops/s | 472.0 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 4 | avgt | 83.88 | - | ns/op | 472.0 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 4 | thrpt | 47939189.35 | - | ops/s | 512.0 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 1 | avgt | 435.16 | - | ns/op | 868.8 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 1 | thrpt | 2315398.98 | - | ops/s | 870.0 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 4 | avgt | 725.00 | - | ns/op | 875.7 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 4 | thrpt | 5547919.00 | - | ops/s | 873.8 B/op |
| Component | MongoDocumentComponentBenchmark.eventStreamToDocument | 1 | avgt | 723.00 | - | ns/op | 4360.0 B/op |
| Component | MongoDocumentComponentBenchmark.eventStreamToDocument | 1 | thrpt | 1384786.81 | - | ops/s | 4336.0 B/op |
| Component | MongoDocumentComponentBenchmark.eventStreamToDocument | 4 | avgt | 862.15 | - | ns/op | 4360.0 B/op |
| Component | MongoDocumentComponentBenchmark.eventStreamToDocument | 4 | thrpt | 4604757.97 | - | ops/s | 4360.0 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 1 | avgt | 1.04 | - | us/op | 4312.0 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 1 | thrpt | 955084.01 | - | ops/s | 4312.0 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 4 | avgt | 1.58 | - | us/op | 4280.0 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 4 | thrpt | 2432207.15 | - | ops/s | 4312.0 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 1 | avgt | 417.57 | - | ns/op | 1000.0 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 1 | thrpt | 2437683.93 | - | ops/s | 1000.0 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 4 | avgt | 698.28 | - | ns/op | 1000.0 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 4 | thrpt | 5552936.25 | - | ops/s | 1000.0 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 1 | avgt | 1.53 | - | us/op | 5312.0 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 1 | thrpt | 657367.70 | - | ops/s | 5312.0 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 4 | avgt | 2.31 | - | us/op | 5280.0 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 4 | thrpt | 1742985.42 | - | ops/s | 5312.0 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 1 | avgt | 212.95 | - | ns/op | 1128.0 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 1 | thrpt | 4718413.10 | - | ops/s | 1128.0 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 4 | avgt | 653.62 | - | ns/op | 1128.0 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 4 | thrpt | 6150675.73 | - | ops/s | 1128.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 1 | avgt | 1.38 | - | us/op | 6048.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 1 | thrpt | 732581.71 | - | ops/s | 6048.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 4 | avgt | 2.00 | - | us/op | 6192.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 4 | thrpt | 1986036.48 | - | ops/s | 6192.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 1 | avgt | 464.08 | - | ns/op | 1240.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 1 | thrpt | 2139505.14 | - | ops/s | 1240.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 4 | avgt | 769.57 | - | ns/op | 1240.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 4 | thrpt | 4903309.40 | - | ops/s | 1240.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 1 | avgt | 1.97 | - | us/op | 7312.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 1 | thrpt | 493449.38 | - | ops/s | 7312.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 4 | avgt | 2.86 | - | us/op | 7432.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 4 | thrpt | 1436804.32 | - | ops/s | 7400.0 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 1 | avgt | 295.48 | - | ns/op | 1672.0 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 1 | thrpt | 3435497.56 | - | ops/s | 1672.0 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 4 | avgt | 1.21 | - | us/op | 1672.0 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 4 | thrpt | 3348486.30 | - | ops/s | 1672.0 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 1 | avgt | 85.32 | - | ns/op | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 1 | thrpt | 11781741.04 | - | ops/s | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 4 | avgt | 568.11 | - | ns/op | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 4 | thrpt | 7082875.99 | - | ops/s | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 1 | avgt | 84.07 | - | ns/op | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 1 | thrpt | 11518279.76 | - | ops/s | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 4 | avgt | 563.29 | - | ns/op | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 4 | thrpt | 6841851.66 | - | ops/s | 544.0 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 1 | avgt | 263.39 | - | ns/op | 1448.0 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 1 | thrpt | 3788512.78 | - | ops/s | 1448.0 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 4 | avgt | 1.41 | - | us/op | 1472.0 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 4 | thrpt | 3156743.96 | - | ops/s | 1464.0 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitRegistration | 1 | avgt | 78.73 | - | ns/op | 560.0 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitRegistration | 1 | thrpt | 12598075.61 | - | ops/s | 560.0 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitRegistration | 4 | avgt | 676.57 | - | ns/op | 560.0 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitRegistration | 4 | thrpt | 5859992.70 | - | ops/s | 560.0 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 1 | avgt | 316.08 | - | ns/op | 1712.0 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 1 | thrpt | 3008157.84 | - | ops/s | 1712.0 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 4 | avgt | 1.20 | - | us/op | 1712.0 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 4 | thrpt | 4430970.11 | - | ops/s | 1736.0 B/op |

## WebFlux Adapter Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickWebFlux`
- **JMH Config**: warmup=0, measurement=1x2s, fork=1, threads=1,4, modes=thrpt, profilers=gc
- **Performance Conclusion Source**: no
- **Source Row Count**: 30
- **Parsed Row Count**: 30

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/webflux/threads-1-webflux.json`
  - Last Modified: 2026-06-15T18:10:17.270Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/webflux/threads-4-webflux.json`
  - Last Modified: 2026-06-15T18:10:59.296Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 1 | thrpt | 33709.86 | - | ops/s | 81189.6 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=1, traceWindowSize=10) | 4 | thrpt | 114395.88 | - | ops/s | 78536.8 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 1 | thrpt | 19804.86 | - | ops/s | 168622.5 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=10, traceWindowSize=10) | 4 | thrpt | 55026.52 | - | ops/s | 167567.7 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 1 | thrpt | 5915.28 | - | ops/s | 759238.1 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.handleTailLimitRequestAndSerialize (eventCount=100, traceWindowSize=10) | 4 | thrpt | 21547.19 | - | ops/s | 752725.4 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=1, traceWindowSize=10) | 1 | thrpt | 848335.96 | - | ops/s | 3305.7 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=1, traceWindowSize=10) | 4 | thrpt | 2396440.95 | - | ops/s | 3266.2 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 1 | thrpt | 67205.76 | - | ops/s | 37262.6 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 4 | thrpt | 247209.14 | - | ops/s | 36968.6 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1 | thrpt | 1646.23 | - | ops/s | 2979595.6 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 4 | thrpt | 5862.60 | - | ops/s | 2962155.2 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=1, traceWindowSize=10) | 1 | thrpt | 757496.98 | - | ops/s | 3848.4 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=1, traceWindowSize=10) | 4 | thrpt | 2339701.63 | - | ops/s | 3803.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=10, traceWindowSize=10) | 1 | thrpt | 70732.65 | - | ops/s | 37708.7 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=10, traceWindowSize=10) | 4 | thrpt | 214170.45 | - | ops/s | 37260.4 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 1 | thrpt | 9276.88 | - | ops/s | 445767.7 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize (eventCount=100, traceWindowSize=10) | 4 | thrpt | 37108.99 | - | ops/s | 444069.1 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.commandResultJsonServerResponseOnly | 1 | thrpt | 832853.85 | - | ops/s | 4520.6 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.commandResultJsonServerResponseOnly | 4 | thrpt | 2516606.96 | - | ops/s | 4454.7 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.extractPreparedCommandMessage | 1 | thrpt | 3698954.34 | - | ops/s | 1381.9 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.extractPreparedCommandMessage | 4 | thrpt | 7635180.34 | - | ops/s | 1374.4 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.handlePreparedAddCartItemRequestWaitSent | 1 | thrpt | 111963.60 | - | ops/s | 12020.4 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.handlePreparedAddCartItemRequestWaitSent | 4 | thrpt | 214939.14 | - | ops/s | 11772.7 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.sendWaitSentCoreFromExtractedMessage | 1 | thrpt | 941804.02 | - | ops/s | 4149.7 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.sendWaitSentCoreFromExtractedMessage | 4 | thrpt | 1327083.39 | - | ops/s | 4139.5 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseServerResponseOnly | 1 | thrpt | 1863150.37 | - | ops/s | 3359.4 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseServerResponseOnly | 4 | thrpt | 6901622.69 | - | ops/s | 3330.0 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.fluxJsonStreamingArrayServerResponseOnly | 1 | thrpt | 5277275.46 | - | ops/s | 1075.1 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.fluxJsonStreamingArrayServerResponseOnly | 4 | thrpt | 21103976.57 | - | ops/s | 1067.1 B/op |


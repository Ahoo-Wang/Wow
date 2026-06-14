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
- **DateTime**: 2026-06-14T11:05:30+08:00
- **CPU Cores**: 14
- **Physical Memory**: 24.0 GiB
- **Benchmark JVM Args**: `-Xmx4g -Xms4g -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+AlwaysPreTouch`
- **JMH Config**: warmup=1x3s, measurement=2x5s, fork=1, threads=1,4, modes=thrpt,avgt

## Framework E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 86987.52 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 93317.17 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 94328.85 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 154346.78 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 164233.31 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 165883.11 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1287280.79 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1297939.35 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1341367.91 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1401523.83 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5507.2 B/op | - | 164233.31 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5482.3 B/op | - | 93317.17 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5317.0 B/op | - | 154346.78 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5303.7 B/op | - | 86987.52 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3959.3 B/op | - | 165883.11 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3953.9 B/op | - | 94328.85 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3262.6 B/op | - | 1341367.91 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3260.2 B/op | - | 1297939.35 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3022.6 B/op | - | 1624347.47 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3020.2 B/op | - | 1401523.83 | ops/s |

## Component Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 21689.46 | - | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 59807.16 | - | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 243669.50 | - | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 346693.82 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 435130.80 | - | ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 505669.31 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 524895.86 | - | ops/s |
| Component | 1 | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 526809.75 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 533303.61 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 535241.00 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062584.0 B/op | - | 59807.16 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062584.0 B/op | - | 21689.46 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 53016.0 B/op | - | 751310.61 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52984.0 B/op | - | 243669.50 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16816.0 B/op | - | 1751808.43 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16816.0 B/op | - | 556280.37 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 11112.1 B/op | - | 1332989.27 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10936.1 B/op | - | 435130.80 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | thrpt | 9240.1 B/op | - | 1572401.86 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | thrpt | 9208.1 B/op | - | 1561005.23 | ops/s |

## Infrastructure E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 2628.54 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 3845.18 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 7829.75 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 12360.62 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 41356.8 B/op | - | 2628.54 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 39064.5 B/op | - | 7829.75 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 7124.1 B/op | - | 3845.18 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 5821.6 B/op | - | 12360.62 | ops/s |

## Group Details

### Primary Framework E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 86987.52 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 93317.17 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 94328.85 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 154346.78 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 164233.31 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 165883.11 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1287280.79 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1297939.35 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1341367.91 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1401523.83 | - | ops/s |

### Primary Framework E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5507.2 B/op | - | 164233.31 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5482.3 B/op | - | 93317.17 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5317.0 B/op | - | 154346.78 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5303.7 B/op | - | 86987.52 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3959.3 B/op | - | 165883.11 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3953.9 B/op | - | 94328.85 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3262.6 B/op | - | 1341367.91 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3260.2 B/op | - | 1297939.35 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3022.6 B/op | - | 1624347.47 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3020.2 B/op | - | 1401523.83 | ops/s |

### Infrastructure E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 2628.54 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 3845.18 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 7829.75 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 12360.62 | - | ops/s |

### Infrastructure E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 41356.8 B/op | - | 2628.54 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 39064.5 B/op | - | 7829.75 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 7124.1 B/op | - | 3845.18 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 5821.6 B/op | - | 12360.62 | ops/s |

### Component Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 21689.46 | - | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 59807.16 | - | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 243669.50 | - | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 346693.82 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 435130.80 | - | ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 505669.31 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 524895.86 | - | ops/s |
| Component | 1 | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 526809.75 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 533303.61 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 535241.00 | - | ops/s |

### Component Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062584.0 B/op | - | 59807.16 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062584.0 B/op | - | 21689.46 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 53016.0 B/op | - | 751310.61 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52984.0 B/op | - | 243669.50 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16816.0 B/op | - | 1751808.43 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16816.0 B/op | - | 556280.37 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 11112.1 B/op | - | 1332989.27 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10936.1 B/op | - | 435130.80 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | thrpt | 9240.1 B/op | - | 1572401.86 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | thrpt | 9208.1 B/op | - | 1561005.23 | ops/s |

## Primary Framework E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickE2E`
- **Performance Conclusion Source**: no
- **Source Row Count**: 24
- **Parsed Row Count**: 24

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-1-framework-e2e.json`
  - Last Modified: 2026-06-14T01:45:33.276Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-4-framework-e2e.json`
  - Last Modified: 2026-06-14T01:48:23.323Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | avgt | 10.43 | - | us/op | 3953.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | thrpt | 94328.85 | - | ops/s | 3953.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 4 | avgt | 24.19 | - | us/op | 3959.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 4 | thrpt | 165883.11 | - | ops/s | 3959.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | avgt | 11.59 | - | us/op | 5248.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | thrpt | 86987.52 | - | ops/s | 5303.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 4 | avgt | 25.69 | - | us/op | 5324.8 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 4 | thrpt | 154346.78 | - | ops/s | 5317.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | avgt | 10.95 | - | us/op | 5483.8 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | thrpt | 93317.17 | - | ops/s | 5482.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 4 | avgt | 24.23 | - | us/op | 5458.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 4 | thrpt | 164233.31 | - | ops/s | 5507.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | avgt | 602.45 | - | ns/op | 2252.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | thrpt | 1603229.23 | - | ops/s | 2252.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 4 | avgt | 3.15 | - | us/op | 2253.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 4 | thrpt | 1287280.79 | - | ops/s | 2253.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | avgt | 722.57 | - | ns/op | 3020.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | thrpt | 1401523.83 | - | ops/s | 3020.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 4 | avgt | 2.34 | - | us/op | 3038.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 4 | thrpt | 1624347.47 | - | ops/s | 3022.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | avgt | 766.13 | - | ns/op | 3260.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | thrpt | 1297939.35 | - | ops/s | 3260.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 4 | avgt | 3.10 | - | us/op | 3262.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 4 | thrpt | 1341367.91 | - | ops/s | 3262.6 B/op |

## Infrastructure E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E`
- **Performance Conclusion Source**: no
- **Source Row Count**: 8
- **Parsed Row Count**: 8

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-1-infrastructure-e2e.json`
  - Last Modified: 2026-06-14T01:49:24.031Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-4-infrastructure-e2e.json`
  - Last Modified: 2026-06-14T01:50:24.947Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | avgt | 382.06 | - | us/op | 41371.8 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | thrpt | 2628.54 | - | ops/s | 41356.8 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | avgt | 508.54 | - | us/op | 39023.1 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | thrpt | 7829.75 | - | ops/s | 39064.5 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | avgt | 266.69 | - | us/op | 7185.7 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | thrpt | 3845.18 | - | ops/s | 7124.1 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | avgt | 324.95 | - | us/op | 5824.3 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | thrpt | 12360.62 | - | ops/s | 5821.6 B/op |

## Component Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickComponent`
- **Performance Conclusion Source**: no
- **Source Row Count**: 260
- **Parsed Row Count**: 260

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-1-component.json`
  - Last Modified: 2026-06-14T02:35:21.080Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-4-component.json`
  - Last Modified: 2026-06-14T03:05:30Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Component | AccessorComponentBenchmark.constructorInvoke0 | 1 | avgt | 3.27 | - | ns/op | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke0 | 1 | thrpt | 308178949.53 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke0 | 4 | avgt | 3.59 | - | ns/op | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke0 | 4 | thrpt | 1186670035.54 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 1 | avgt | 3.39 | - | ns/op | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 1 | thrpt | 292439596.05 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 4 | avgt | 4.10 | - | ns/op | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 4 | thrpt | 1096459364.66 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 1 | avgt | 3.63 | - | ns/op | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 1 | thrpt | 274984452.86 | - | ops/s | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 4 | avgt | 4.37 | - | ns/op | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 4 | thrpt | 918208586.94 | - | ops/s | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 1 | avgt | 5.07 | - | ns/op | 40.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 1 | thrpt | 197595442.66 | - | ops/s | 40.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 4 | avgt | 5.74 | - | ns/op | 40.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 4 | thrpt | 702805490.26 | - | ops/s | 40.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 1 | avgt | 8.63 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 1 | thrpt | 115144208.69 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 4 | avgt | 9.75 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 4 | thrpt | 401744857.09 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 1 | avgt | 7.41 | - | ns/op | 56.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 1 | thrpt | 129321232.31 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 4 | avgt | 8.68 | - | ns/op | 56.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 4 | thrpt | 476482492.02 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 1 | avgt | 8.46 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 1 | thrpt | 119182414.08 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 4 | avgt | 10.10 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 4 | thrpt | 415432926.56 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 1 | avgt | 7.48 | - | ns/op | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 1 | thrpt | 137005604.37 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 4 | avgt | 8.55 | - | ns/op | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 4 | thrpt | 478399036.28 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 1 | avgt | 8.16 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 1 | thrpt | 124717318.51 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 4 | avgt | 9.62 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 4 | thrpt | 412445789.09 | - | ops/s | 80.0 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 1 | avgt | 261.43 | - | ns/op | 1840.0 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 1 | thrpt | 3824558.12 | - | ops/s | 1840.0 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 4 | avgt | 2.13 | - | us/op | 1840.0 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 4 | thrpt | 1927757.57 | - | ops/s | 1840.0 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 1 | avgt | 110.73 | - | ns/op | 904.0 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 1 | thrpt | 8976233.19 | - | ops/s | 904.0 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 4 | avgt | 687.81 | - | ns/op | 904.0 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 4 | thrpt | 6045729.12 | - | ops/s | 904.0 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 1 | avgt | 451.04 | - | ns/op | 2912.0 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 1 | thrpt | 2181304.41 | - | ops/s | 2912.0 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 4 | avgt | 2.85 | - | us/op | 2912.0 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 4 | thrpt | 1369450.49 | - | ops/s | 2912.0 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 1 | avgt | 823.54 | - | ns/op | 5208.0 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 1 | thrpt | 1232753.99 | - | ops/s | 5208.0 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 4 | avgt | 3.57 | - | us/op | 5216.0 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 4 | thrpt | 1064987.29 | - | ops/s | 5232.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 1 | avgt | 147.21 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 1 | thrpt | 6822074.70 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 4 | avgt | 170.07 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 4 | thrpt | 23075212.42 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 1 | avgt | 147.50 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 1 | thrpt | 6874390.40 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 4 | avgt | 170.01 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 4 | thrpt | 22841387.10 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 1 | avgt | 146.61 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 1 | thrpt | 6853841.46 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 4 | avgt | 164.19 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 4 | thrpt | 23041521.32 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 1 | avgt | 144.79 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 1 | thrpt | 6905611.31 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 4 | avgt | 177.25 | - | ns/op | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 4 | thrpt | 23949005.60 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 1 | avgt | 932.10 | - | ns/op | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 1 | thrpt | 1072861.42 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 4 | avgt | 1.48 | - | us/op | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 4 | thrpt | 2183529.69 | - | ops/s | 5128.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 1 | avgt | 930.66 | - | ns/op | 5128.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 1 | thrpt | 1078986.66 | - | ops/s | 5128.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 4 | avgt | 1.58 | - | us/op | 5128.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 4 | thrpt | 2612075.64 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 1 | avgt | 946.43 | - | ns/op | 5128.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 1 | thrpt | 1073172.58 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 4 | avgt | 1.62 | - | us/op | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 4 | thrpt | 2319914.92 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 1 | avgt | 910.69 | - | ns/op | 5128.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 1 | thrpt | 1086537.29 | - | ops/s | 5128.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 4 | avgt | 1.62 | - | us/op | 5128.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 4 | thrpt | 2402695.37 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 1 | avgt | 351.35 | - | ns/op | 2256.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 1 | thrpt | 2696549.48 | - | ops/s | 2256.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 4 | avgt | 397.99 | - | ns/op | 2256.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 4 | thrpt | 10505250.28 | - | ops/s | 2256.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 1 | avgt | 4.27 | - | us/op | 52984.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 1 | thrpt | 243669.50 | - | ops/s | 52984.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 4 | avgt | 5.19 | - | us/op | 52984.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 4 | thrpt | 751310.61 | - | ops/s | 53016.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 1 | avgt | 1.77 | - | us/op | 16816.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 1 | thrpt | 556280.37 | - | ops/s | 16816.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 4 | avgt | 2.01 | - | us/op | 16816.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 4 | thrpt | 1751808.43 | - | ops/s | 16816.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 1 | avgt | 44.84 | - | us/op | 1062609.2 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 1 | thrpt | 21689.46 | - | ops/s | 1062584.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 4 | avgt | 71.09 | - | us/op | 1062584.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 4 | thrpt | 59807.16 | - | ops/s | 1062584.0 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 1 | avgt | 2.10 | - | ns/op | 32.0 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 1 | thrpt | 475408106.50 | - | ops/s | 32.0 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 4 | avgt | 3.26 | - | ns/op | 32.0 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 4 | thrpt | 1401424019.18 | - | ops/s | 32.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 1 | avgt | 48.33 | - | ns/op | 240.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 1 | thrpt | 20563004.73 | - | ops/s | 240.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 4 | avgt | 662.50 | - | ns/op | 240.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 4 | thrpt | 5963743.69 | - | ops/s | 240.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 1 | avgt | 55.61 | - | ns/op | 272.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 1 | thrpt | 18101163.69 | - | ops/s | 272.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 4 | avgt | 680.21 | - | ns/op | 272.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 4 | thrpt | 5766980.85 | - | ops/s | 272.0 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 1 | avgt | 15.58 | - | ns/op | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 1 | thrpt | 65693175.71 | - | ops/s | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 4 | avgt | 19.55 | - | ns/op | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 4 | thrpt | 189333396.80 | - | ops/s | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 1 | avgt | 199.25 | - | ns/op | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 1 | thrpt | 5450461.13 | - | ops/s | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 4 | avgt | 2.08 | - | us/op | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 4 | thrpt | 1951660.14 | - | ops/s | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 1 | avgt | 3.68 | - | ns/op | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 1 | thrpt | 274663581.94 | - | ops/s | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 4 | avgt | 4.00 | - | ns/op | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 4 | thrpt | 996384100.90 | - | ops/s | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 1 | avgt | 201.08 | - | ns/op | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 1 | thrpt | 5516684.54 | - | ops/s | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 4 | avgt | 2.16 | - | us/op | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 4 | thrpt | 1764490.23 | - | ops/s | 1080.0 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 1 | avgt | 2.33 | - | us/op | 10920.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 1 | thrpt | 435130.80 | - | ops/s | 10936.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 4 | avgt | 2.75 | - | us/op | 11040.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 4 | thrpt | 1332989.27 | - | ops/s | 11112.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 1 | avgt | 1.84 | - | us/op | 9176.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 1 | thrpt | 535241.00 | - | ops/s | 9176.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 4 | avgt | 2.34 | - | us/op | 9256.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 4 | thrpt | 1572401.86 | - | ops/s | 9240.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 1 | avgt | 1.43 | - | us/op | 7992.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 1 | thrpt | 729242.93 | - | ops/s | 7992.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 4 | avgt | 2.30 | - | us/op | 7992.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 4 | thrpt | 1803918.68 | - | ops/s | 7992.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 1 | avgt | 1.90 | - | us/op | 9208.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 1 | thrpt | 533303.61 | - | ops/s | 9144.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 4 | avgt | 2.43 | - | us/op | 9224.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 4 | thrpt | 1561005.23 | - | ops/s | 9208.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 1 | avgt | 1.36 | - | us/op | 7696.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 1 | thrpt | 765039.56 | - | ops/s | 7720.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 4 | avgt | 2.25 | - | us/op | 7696.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 4 | thrpt | 1478377.29 | - | ops/s | 7688.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 1 | avgt | 1.20 | - | us/op | 6608.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 1 | thrpt | 851946.93 | - | ops/s | 6608.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 4 | avgt | 2.58 | - | us/op | 6656.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 4 | thrpt | 1521792.79 | - | ops/s | 6584.1 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 1 | avgt | 1.98 | - | us/op | 6748.9 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 1 | thrpt | 524895.86 | - | ops/s | 6636.1 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 4 | avgt | 11.76 | - | us/op | 7000.1 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 4 | thrpt | 346693.82 | - | ops/s | 7013.0 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 1 | avgt | 206.79 | - | ns/op | 1096.0 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 1 | thrpt | 4987916.06 | - | ops/s | 1048.0 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 4 | avgt | 231.64 | - | ns/op | 1048.0 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 4 | thrpt | 17719036.04 | - | ops/s | 1048.0 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 1 | avgt | 70.82 | - | ns/op | 608.0 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 1 | thrpt | 15598204.18 | - | ops/s | 608.0 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 4 | avgt | 78.62 | - | ns/op | 608.0 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 4 | thrpt | 47448464.82 | - | ops/s | 608.0 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 1 | avgt | 21.83 | - | ns/op | 104.0 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 1 | thrpt | 46813214.02 | - | ops/s | 104.0 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 4 | avgt | 23.80 | - | ns/op | 104.0 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 4 | thrpt | 177366689.41 | - | ops/s | 48.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 1 | avgt | 45.16 | - | ns/op | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 1 | thrpt | 22058793.14 | - | ops/s | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 4 | avgt | 63.26 | - | ns/op | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 4 | thrpt | 74221269.54 | - | ops/s | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 1 | avgt | 49.22 | - | ns/op | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 1 | thrpt | 21048989.95 | - | ops/s | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 4 | avgt | 52.48 | - | ns/op | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 4 | thrpt | 72374445.52 | - | ops/s | 168.0 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 1 | avgt | 444.69 | - | ns/op | 2664.2 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 1 | thrpt | 2316789.45 | - | ops/s | 2664.2 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 4 | avgt | 2.89 | - | us/op | 2639.8 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 4 | thrpt | 1413687.76 | - | ops/s | 2616.0 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 1 | avgt | 368.34 | - | ns/op | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 1 | thrpt | 2754395.66 | - | ops/s | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 4 | avgt | 2.77 | - | us/op | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 4 | thrpt | 1438170.52 | - | ops/s | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 1 | avgt | 1.98 | - | us/op | 7288.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 1 | thrpt | 526809.75 | - | ops/s | 7288.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 4 | avgt | 2.77 | - | us/op | 7400.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 4 | thrpt | 1277885.65 | - | ops/s | 7336.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 1 | avgt | 68.88 | - | ns/op | 568.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 1 | thrpt | 16330333.71 | - | ops/s | 568.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 4 | avgt | 78.05 | - | ns/op | 568.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 4 | thrpt | 45630308.38 | - | ops/s | 568.0 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 1 | avgt | 367.25 | - | ns/op | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 1 | thrpt | 2720184.06 | - | ops/s | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 4 | avgt | 2.99 | - | us/op | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 4 | thrpt | 1328383.91 | - | ops/s | 2168.0 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 1 | avgt | 492.92 | - | ns/op | 870.8 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 1 | thrpt | 2443411.31 | - | ops/s | 865.7 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 4 | avgt | 793.41 | - | ns/op | 861.4 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 4 | thrpt | 5104340.95 | - | ops/s | 879.5 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 1 | avgt | 70.11 | - | ns/op | 512.0 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 1 | thrpt | 14735702.45 | - | ops/s | 512.0 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 4 | avgt | 76.62 | - | ns/op | 472.0 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 4 | thrpt | 58581732.21 | - | ops/s | 472.0 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 1 | avgt | 492.83 | - | ns/op | 860.2 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 1 | thrpt | 2428272.38 | - | ops/s | 868.0 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 4 | avgt | 755.55 | - | ns/op | 857.5 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 4 | thrpt | 4679709.12 | - | ops/s | 856.5 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 1 | avgt | 1.07 | - | us/op | 4256.0 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 1 | thrpt | 961688.56 | - | ops/s | 4312.0 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 4 | avgt | 1.60 | - | us/op | 4280.0 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 4 | thrpt | 2261061.50 | - | ops/s | 4304.0 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 1 | avgt | 412.67 | - | ns/op | 1000.0 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 1 | thrpt | 2602408.19 | - | ops/s | 1000.0 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 4 | avgt | 744.62 | - | ns/op | 1000.0 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 4 | thrpt | 5331621.26 | - | ops/s | 1000.0 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 1 | avgt | 1.60 | - | us/op | 5312.0 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 1 | thrpt | 653734.41 | - | ops/s | 5312.0 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 4 | avgt | 2.31 | - | us/op | 5280.0 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 4 | thrpt | 1569068.22 | - | ops/s | 5280.0 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 1 | avgt | 220.35 | - | ns/op | 1128.0 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 1 | thrpt | 5013606.03 | - | ops/s | 1128.0 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 4 | avgt | 647.04 | - | ns/op | 1128.0 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 4 | thrpt | 4579828.39 | - | ops/s | 1128.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 1 | avgt | 1.44 | - | us/op | 6160.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 1 | thrpt | 733476.38 | - | ops/s | 6048.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 4 | avgt | 2.06 | - | us/op | 6192.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 4 | thrpt | 1774807.82 | - | ops/s | 6160.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 1 | avgt | 462.22 | - | ns/op | 1240.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 1 | thrpt | 2198104.85 | - | ops/s | 1240.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 4 | avgt | 817.99 | - | ns/op | 1240.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 4 | thrpt | 4920463.68 | - | ops/s | 1240.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 1 | avgt | 1.97 | - | us/op | 7312.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 1 | thrpt | 505669.31 | - | ops/s | 7312.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 4 | avgt | 2.93 | - | us/op | 7424.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 4 | thrpt | 1344314.98 | - | ops/s | 7400.0 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 1 | avgt | 301.35 | - | ns/op | 1672.0 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 1 | thrpt | 3361449.47 | - | ops/s | 1672.0 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 4 | avgt | 1.28 | - | us/op | 1672.0 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 4 | thrpt | 3485377.60 | - | ops/s | 1672.0 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 1 | avgt | 85.97 | - | ns/op | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 1 | thrpt | 11740312.78 | - | ops/s | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 4 | avgt | 649.52 | - | ns/op | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 4 | thrpt | 7396112.50 | - | ops/s | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 1 | avgt | 86.54 | - | ns/op | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 1 | thrpt | 11887424.08 | - | ops/s | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 4 | avgt | 643.64 | - | ns/op | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 4 | thrpt | 7296487.90 | - | ops/s | 544.0 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 1 | avgt | 278.79 | - | ns/op | 1472.0 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 1 | thrpt | 3800671.66 | - | ops/s | 1448.0 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 4 | avgt | 1.28 | - | us/op | 1448.0 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 4 | thrpt | 3893786.33 | - | ops/s | 1496.0 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitRegistration | 1 | avgt | 80.52 | - | ns/op | 560.0 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitRegistration | 1 | thrpt | 12521197.68 | - | ops/s | 560.0 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitRegistration | 4 | avgt | 686.95 | - | ns/op | 560.0 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitRegistration | 4 | thrpt | 6131974.56 | - | ops/s | 560.0 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 1 | avgt | 323.53 | - | ns/op | 1712.0 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 1 | thrpt | 3012503.72 | - | ops/s | 1712.0 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 4 | avgt | 1.49 | - | us/op | 1728.0 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 4 | thrpt | 3827046.58 | - | ops/s | 1712.0 B/op |


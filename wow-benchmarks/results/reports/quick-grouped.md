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
- **DateTime**: 2026-06-14T09:50:25+08:00
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
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 20433.29 | - | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 52670.30 | - | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 216245.09 | - | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 224127.53 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 227949.23 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 289121.22 | - | ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 370659.85 | - | ops/s |
| Component | 1 | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 387854.49 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 441111.51 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 464050.46 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062584.0 B/op | - | 52670.30 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062584.0 B/op | - | 20433.29 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52984.0 B/op | - | 683754.05 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52984.0 B/op | - | 216245.09 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16816.0 B/op | - | 1558026.34 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16816.0 B/op | - | 501294.85 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 12435.3 B/op | - | 870905.27 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 12298.4 B/op | - | 289121.22 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | thrpt | 9240.3 B/op | - | 1094975.64 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | thrpt | 9208.4 B/op | - | 464050.46 | ops/s |

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
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 20433.29 | - | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 52670.30 | - | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 216245.09 | - | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 224127.53 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 227949.23 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 289121.22 | - | ops/s |
| Component | 1 | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 370659.85 | - | ops/s |
| Component | 1 | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 387854.49 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 441111.51 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 464050.46 | - | ops/s |

### Component Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062584.0 B/op | - | 52670.30 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062584.0 B/op | - | 20433.29 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52984.0 B/op | - | 683754.05 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52984.0 B/op | - | 216245.09 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16816.0 B/op | - | 1558026.34 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16816.0 B/op | - | 501294.85 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 12435.3 B/op | - | 870905.27 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 12298.4 B/op | - | 289121.22 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | thrpt | 9240.3 B/op | - | 1094975.64 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | thrpt | 9208.4 B/op | - | 464050.46 | ops/s |

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
- **Source Row Count**: 130
- **Parsed Row Count**: 130

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-1-component.json`
  - Last Modified: 2026-06-11T05:17:22.199Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-4-component.json`
  - Last Modified: 2026-06-11T05:22:53.003Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Component | AccessorComponentBenchmark.constructorInvoke0 | 1 | thrpt | 277280207.67 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke0 | 4 | thrpt | 845970430.13 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 1 | thrpt | 252570005.11 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 4 | thrpt | 669978645.47 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 1 | thrpt | 249127257.48 | - | ops/s | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 4 | thrpt | 639062502.94 | - | ops/s | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 1 | thrpt | 178983728.69 | - | ops/s | 40.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 4 | thrpt | 513964852.43 | - | ops/s | 40.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 1 | thrpt | 107705548.90 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 4 | thrpt | 299547733.65 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 1 | thrpt | 116890166.96 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 4 | thrpt | 349559977.91 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 1 | thrpt | 109882979.38 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 4 | thrpt | 312376690.85 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 1 | thrpt | 127322490.33 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 4 | thrpt | 356472945.42 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 1 | thrpt | 105337836.59 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 4 | thrpt | 307427902.08 | - | ops/s | 80.0 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 1 | thrpt | 3171063.13 | - | ops/s | 1840.0 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 4 | thrpt | 1430071.48 | - | ops/s | 1840.0 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 1 | thrpt | 7641796.88 | - | ops/s | 904.0 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 4 | thrpt | 4230945.81 | - | ops/s | 904.0 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 1 | thrpt | 1867806.35 | - | ops/s | 2912.0 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 4 | thrpt | 1060130.80 | - | ops/s | 2880.0 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 1 | thrpt | 1004880.39 | - | ops/s | 5208.0 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 4 | thrpt | 733900.64 | - | ops/s | 5216.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 1 | thrpt | 6285204.20 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 4 | thrpt | 18166591.27 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 1 | thrpt | 6172466.83 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 4 | thrpt | 18957580.41 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 1 | thrpt | 6254586.26 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 4 | thrpt | 18707451.82 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 1 | thrpt | 4977697.66 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 4 | thrpt | 19194066.29 | - | ops/s | 1320.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 1 | thrpt | 939763.38 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 4 | thrpt | 2037006.83 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 1 | thrpt | 943534.76 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 4 | thrpt | 2009651.80 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 1 | thrpt | 825653.78 | - | ops/s | 5128.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 4 | thrpt | 2028066.91 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 1 | thrpt | 954411.52 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 4 | thrpt | 2050424.22 | - | ops/s | 5160.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 1 | thrpt | 2430828.90 | - | ops/s | 2256.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 4 | thrpt | 7496706.48 | - | ops/s | 2256.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 1 | thrpt | 216245.09 | - | ops/s | 52984.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 4 | thrpt | 683754.05 | - | ops/s | 52984.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 1 | thrpt | 501294.85 | - | ops/s | 16816.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 4 | thrpt | 1558026.34 | - | ops/s | 16816.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 1 | thrpt | 20433.29 | - | ops/s | 1062584.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 4 | thrpt | 52670.30 | - | ops/s | 1062584.0 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 1 | thrpt | 432978691.33 | - | ops/s | 32.0 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 4 | thrpt | 1181623904.17 | - | ops/s | 32.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 1 | thrpt | 18059878.01 | - | ops/s | 240.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 4 | thrpt | 5159070.01 | - | ops/s | 240.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 1 | thrpt | 16165884.24 | - | ops/s | 272.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 4 | thrpt | 4599697.91 | - | ops/s | 272.0 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 1 | thrpt | 64211696.41 | - | ops/s | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 4 | thrpt | 175944158.51 | - | ops/s | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 1 | thrpt | 4869204.97 | - | ops/s | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 4 | thrpt | 1521505.83 | - | ops/s | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 1 | thrpt | 258958076.15 | - | ops/s | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 4 | thrpt | 834796237.41 | - | ops/s | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 1 | thrpt | 4777315.62 | - | ops/s | 1080.0 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 4 | thrpt | 1570853.52 | - | ops/s | 1080.0 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 1 | thrpt | 289121.22 | - | ops/s | 12298.4 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 4 | thrpt | 870905.27 | - | ops/s | 12435.3 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 1 | thrpt | 441111.51 | - | ops/s | 9176.4 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 4 | thrpt | 1094975.64 | - | ops/s | 9240.3 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 1 | thrpt | 591689.58 | - | ops/s | 7992.3 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 4 | thrpt | 832731.07 | - | ops/s | 8008.4 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 1 | thrpt | 464050.46 | - | ops/s | 9208.4 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 4 | thrpt | 1138738.51 | - | ops/s | 9208.3 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 1 | thrpt | 598996.14 | - | ops/s | 7720.3 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 4 | thrpt | 829937.32 | - | ops/s | 7696.4 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 1 | thrpt | 638322.71 | - | ops/s | 6624.3 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 4 | thrpt | 822747.37 | - | ops/s | 6617.4 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 1 | thrpt | 227949.23 | - | ops/s | 2753.8 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 4 | thrpt | 224127.53 | - | ops/s | 2929.0 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 1 | thrpt | 4047909.85 | - | ops/s | 1112.0 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 4 | thrpt | 14765303.90 | - | ops/s | 1096.0 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 1 | thrpt | 12365813.29 | - | ops/s | 608.0 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 4 | thrpt | 42063607.71 | - | ops/s | 608.0 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 1 | thrpt | 38462780.95 | - | ops/s | 104.0 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 4 | thrpt | 131550626.10 | - | ops/s | 104.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 1 | thrpt | 18278922.22 | - | ops/s | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 4 | thrpt | 66061121.59 | - | ops/s | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 1 | thrpt | 17052847.89 | - | ops/s | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 4 | thrpt | 63228857.09 | - | ops/s | 168.0 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 1 | thrpt | 1828387.10 | - | ops/s | 2664.2 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 4 | thrpt | 1026088.34 | - | ops/s | 2638.6 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 1 | thrpt | 2197187.66 | - | ops/s | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 4 | thrpt | 1085132.57 | - | ops/s | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 1 | thrpt | 387854.49 | - | ops/s | 7288.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 4 | thrpt | 1183282.54 | - | ops/s | 7424.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 1 | thrpt | 13992937.62 | - | ops/s | 568.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 4 | thrpt | 47128760.12 | - | ops/s | 568.0 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 1 | thrpt | 2264906.32 | - | ops/s | 2168.0 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 4 | thrpt | 1138111.84 | - | ops/s | 2200.0 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 1 | thrpt | 1405144.76 | - | ops/s | 904.0 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 4 | thrpt | 4235375.75 | - | ops/s | 848.0 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 1 | thrpt | 12318519.49 | - | ops/s | 512.0 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 4 | thrpt | 51721167.46 | - | ops/s | 472.0 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 1 | thrpt | 1490865.06 | - | ops/s | 904.0 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 4 | thrpt | 4124051.65 | - | ops/s | 848.0 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 1 | thrpt | 782792.05 | - | ops/s | 4312.0 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 4 | thrpt | 2256828.94 | - | ops/s | 4280.0 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 1 | thrpt | 1988215.40 | - | ops/s | 1000.0 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 4 | thrpt | 4788616.91 | - | ops/s | 1000.0 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 1 | thrpt | 546977.40 | - | ops/s | 5312.0 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 4 | thrpt | 1472582.16 | - | ops/s | 5280.0 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 1 | thrpt | 4410141.27 | - | ops/s | 1128.0 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 4 | thrpt | 5550840.91 | - | ops/s | 1128.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 1 | thrpt | 599925.86 | - | ops/s | 6048.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 4 | thrpt | 1729732.18 | - | ops/s | 6192.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 1 | thrpt | 1602752.75 | - | ops/s | 1240.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 4 | thrpt | 4134749.93 | - | ops/s | 1240.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 1 | thrpt | 370659.85 | - | ops/s | 7312.0 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 4 | thrpt | 993029.76 | - | ops/s | 7424.0 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 1 | thrpt | 3011932.03 | - | ops/s | 1672.0 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 4 | thrpt | 3111881.17 | - | ops/s | 1672.0 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 1 | thrpt | 11326529.67 | - | ops/s | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 4 | thrpt | 6628671.96 | - | ops/s | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 1 | thrpt | 11200007.39 | - | ops/s | 544.0 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 4 | thrpt | 6717988.51 | - | ops/s | 544.0 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 1 | thrpt | 2911491.75 | - | ops/s | 2496.0 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 4 | thrpt | 1949333.62 | - | ops/s | 2448.0 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitStrategy | 1 | thrpt | 5746144.29 | - | ops/s | 1592.0 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitStrategy | 4 | thrpt | 4147296.57 | - | ops/s | 1592.0 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 1 | thrpt | 2026698.88 | - | ops/s | 3016.0 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 4 | thrpt | 1934331.60 | - | ops/s | 3056.0 B/op |


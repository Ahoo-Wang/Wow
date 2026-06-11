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
- **JVM**: OpenJDK 64-Bit Server VM 17.0.19+10
- **OS**: Windows 10 10.0 amd64
- **DateTime**: 2026-06-11T15:03:03+08:00
- **CPU Cores**: 24
- **Physical Memory**: 63.8 GiB
- **Benchmark JVM Args**: `-Xmx4g -Xms4g -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+AlwaysPreTouch`
- **JMH Config**: warmup=1x3s, measurement=2x5s, fork=1, threads=1,4, modes=thrpt,avgt

## Framework E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 41019.00 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 42167.30 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 46618.17 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 144306.22 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 155635.24 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 167912.52 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 790425.59 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 838233.12 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1175740.21 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1279260.44 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 14770.9 B/op | - | 41019.00 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 14547.2 B/op | - | 42167.30 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 14480.2 B/op | - | 144306.22 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 14281.1 B/op | - | 155635.24 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 12874.0 B/op | - | 46618.17 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 12714.9 B/op | - | 167912.52 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3231.5 B/op | - | 1454136.36 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3229.0 B/op | - | 790425.59 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3030.2 B/op | - | 1519198.30 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 2988.9 B/op | - | 838233.12 | ops/s |

## Component Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 5929.78 | - | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 13341.87 | - | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 40783.08 | - | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 83177.64 | - | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 180821.49 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 213117.94 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 271851.00 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 274053.18 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 317723.28 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 319831.94 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062617.2 B/op | - | 13341.87 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062602.6 B/op | - | 5929.78 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52921.5 B/op | - | 180821.49 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52904.5 B/op | - | 40783.08 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16720.1 B/op | - | 83177.64 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16716.2 B/op | - | 407456.27 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | thrpt | 10625.8 B/op | - | 319831.94 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | thrpt | 10571.0 B/op | - | 550559.07 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10541.2 B/op | - | 493788.79 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10500.1 B/op | - | 213117.94 | ops/s |

## Infrastructure E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 524.86 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 660.45 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 2118.74 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 2561.48 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 122139.1 B/op | - | 524.86 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 89645.1 B/op | - | 2118.74 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 55761.8 B/op | - | 660.45 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 35797.3 B/op | - | 2561.48 | ops/s |

## Group Details

### Primary Framework E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 41019.00 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 42167.30 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 46618.17 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 144306.22 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 155635.24 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 167912.52 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 790425.59 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 838233.12 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1175740.21 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1279260.44 | - | ops/s |

### Primary Framework E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 14770.9 B/op | - | 41019.00 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 14547.2 B/op | - | 42167.30 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 14480.2 B/op | - | 144306.22 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 14281.1 B/op | - | 155635.24 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 12874.0 B/op | - | 46618.17 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 12714.9 B/op | - | 167912.52 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3231.5 B/op | - | 1454136.36 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3229.0 B/op | - | 790425.59 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3030.2 B/op | - | 1519198.30 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 2988.9 B/op | - | 838233.12 | ops/s |

### Infrastructure E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 524.86 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 660.45 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 2118.74 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 2561.48 | - | ops/s |

### Infrastructure E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 122139.1 B/op | - | 524.86 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 89645.1 B/op | - | 2118.74 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 55761.8 B/op | - | 660.45 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 35797.3 B/op | - | 2561.48 | ops/s |

### Component Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 5929.78 | - | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 13341.87 | - | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 40783.08 | - | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 83177.64 | - | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 180821.49 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 213117.94 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 271851.00 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 274053.18 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 317723.28 | - | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 319831.94 | - | ops/s |

### Component Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062617.2 B/op | - | 13341.87 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | thrpt | 1062602.6 B/op | - | 5929.78 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52921.5 B/op | - | 180821.49 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | thrpt | 52904.5 B/op | - | 40783.08 | ops/s |
| Component | 1 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16720.1 B/op | - | 83177.64 | ops/s |
| Component | 4 | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | thrpt | 16716.2 B/op | - | 407456.27 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | thrpt | 10625.8 B/op | - | 319831.94 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.sendCommandFireAndForget | thrpt | 10571.0 B/op | - | 550559.07 | ops/s |
| Component | 4 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10541.2 B/op | - | 493788.79 | ops/s |
| Component | 1 | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | thrpt | 10500.1 B/op | - | 213117.94 | ops/s |

## Primary Framework E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickE2E`
- **Performance Conclusion Source**: no
- **Source Row Count**: 24
- **Parsed Row Count**: 24

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-1-framework-e2e.json`
  - Last Modified: 2026-06-11T05:49:10.626Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-4-framework-e2e.json`
  - Last Modified: 2026-06-11T05:52:09.603Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | avgt | 21.14 | - | us/op | 12871.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | thrpt | 46618.17 | - | ops/s | 12874.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 4 | avgt | 23.68 | - | us/op | 12714.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 4 | thrpt | 167912.52 | - | ops/s | 12714.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | avgt | 24.38 | - | us/op | 14794.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | thrpt | 41019.00 | - | ops/s | 14770.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 4 | avgt | 27.82 | - | us/op | 14537.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 4 | thrpt | 144306.22 | - | ops/s | 14480.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | avgt | 23.66 | - | us/op | 14545.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | thrpt | 42167.30 | - | ops/s | 14547.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 4 | avgt | 25.50 | - | us/op | 14344.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 4 | thrpt | 155635.24 | - | ops/s | 14281.1 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | avgt | 857.64 | - | ns/op | 2292.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | thrpt | 1175740.21 | - | ops/s | 2292.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 4 | avgt | 3.33 | - | us/op | 2254.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 4 | thrpt | 1279260.44 | - | ops/s | 2295.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | avgt | 1.19 | - | us/op | 2972.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | thrpt | 838233.12 | - | ops/s | 2988.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 4 | avgt | 2.44 | - | us/op | 3046.1 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 4 | thrpt | 1519198.30 | - | ops/s | 3030.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | avgt | 1.28 | - | us/op | 3213.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | thrpt | 790425.59 | - | ops/s | 3229.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 4 | avgt | 2.93 | - | us/op | 3247.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 4 | thrpt | 1454136.36 | - | ops/s | 3231.5 B/op |

## Infrastructure E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E`
- **Performance Conclusion Source**: no
- **Source Row Count**: 8
- **Parsed Row Count**: 8

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-1-infrastructure-e2e.json`
  - Last Modified: 2026-06-11T06:59:48.789Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-4-infrastructure-e2e.json`
  - Last Modified: 2026-06-11T07:00:54.901Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | avgt | 1.97 | - | ms/op | 123578.0 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | thrpt | 524.86 | - | ops/s | 122139.1 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | avgt | 2.11 | - | ms/op | 91225.8 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | thrpt | 2118.74 | - | ops/s | 89645.1 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | avgt | 1.43 | - | ms/op | 53964.8 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | thrpt | 660.45 | - | ops/s | 55761.8 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | avgt | 1.49 | - | ms/op | 35454.2 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | thrpt | 2561.48 | - | ops/s | 35797.3 B/op |

## Component Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickComponent`
- **Performance Conclusion Source**: no
- **Source Row Count**: 260
- **Parsed Row Count**: 260

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-1-component.json`
  - Last Modified: 2026-06-11T06:23:32.999Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-4-component.json`
  - Last Modified: 2026-06-11T06:54:46.446Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Component | AccessorComponentBenchmark.constructorInvoke0 | 1 | avgt | 2.63 | - | ns/op | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke0 | 1 | thrpt | 391556822.15 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke0 | 4 | avgt | 5.70 | - | ns/op | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke0 | 4 | thrpt | 848543702.18 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 1 | avgt | 3.08 | - | ns/op | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 1 | thrpt | 338788097.29 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 4 | avgt | 5.36 | - | ns/op | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke1 | 4 | thrpt | 775603221.84 | - | ops/s | 16.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 1 | avgt | 4.08 | - | ns/op | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 1 | thrpt | 254679829.61 | - | ops/s | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 4 | avgt | 7.71 | - | ns/op | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvoke2 | 4 | thrpt | 556720141.87 | - | ops/s | 24.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 1 | avgt | 4.29 | - | ns/op | 40.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 1 | thrpt | 244602422.93 | - | ops/s | 40.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 4 | avgt | 9.90 | - | ns/op | 40.0 B/op |
| Component | AccessorComponentBenchmark.constructorInvokeArray | 4 | thrpt | 399009699.04 | - | ops/s | 40.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 1 | avgt | 11.13 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 1 | thrpt | 93200093.22 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 4 | avgt | 22.21 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke | 4 | thrpt | 180400125.63 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 1 | avgt | 9.92 | - | ns/op | 56.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 1 | thrpt | 104572293.37 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 4 | avgt | 17.94 | - | ns/op | 56.0 B/op |
| Component | AccessorComponentBenchmark.functionAccessorInvoke1 | 4 | thrpt | 235145160.21 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 1 | avgt | 11.00 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 1 | thrpt | 93880451.57 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 4 | avgt | 22.16 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleArray | 4 | thrpt | 180082426.19 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 1 | avgt | 9.90 | - | ns/op | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 1 | thrpt | 106553666.68 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 4 | avgt | 17.69 | - | ns/op | 56.0 B/op |
| Component | AccessorComponentBenchmark.methodHandleSingle | 4 | thrpt | 235030265.62 | - | ops/s | 56.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 1 | avgt | 10.53 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 1 | thrpt | 98698635.15 | - | ops/s | 80.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 4 | avgt | 21.73 | - | ns/op | 80.0 B/op |
| Component | AccessorComponentBenchmark.reflectionInvokeVarargs | 4 | thrpt | 246312102.80 | - | ops/s | 56.0 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 1 | avgt | 326.99 | - | ns/op | 1744.2 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 1 | thrpt | 3258619.39 | - | ops/s | 1744.2 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 4 | avgt | 1.79 | - | us/op | 1768.8 B/op |
| Component | AggregateHandleComponentBenchmark.createCommandAggregate | 4 | thrpt | 2368232.11 | - | ops/s | 1768.7 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 1 | avgt | 131.95 | - | ns/op | 832.1 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 1 | thrpt | 7847873.60 | - | ops/s | 832.1 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 4 | avgt | 588.83 | - | ns/op | 832.3 B/op |
| Component | AggregateHandleComponentBenchmark.createEmptyStateAggregate | 4 | thrpt | 6882564.10 | - | ops/s | 832.2 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 1 | avgt | 584.57 | - | ns/op | 2816.4 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 1 | thrpt | 1756544.27 | - | ops/s | 2816.4 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 4 | avgt | 2.40 | - | us/op | 2817.0 B/op |
| Component | AggregateHandleComponentBenchmark.createStateAggregateAndApplyEventStream | 4 | thrpt | 1668594.10 | - | ops/s | 2817.0 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 1 | avgt | 1.44 | - | us/op | 4929.0 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 1 | thrpt | 728199.53 | - | ops/s | 4889.0 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 4 | avgt | 3.20 | - | us/op | 4929.4 B/op |
| Component | AggregateHandleComponentBenchmark.processCommandAggregate | 4 | thrpt | 1335457.85 | - | ops/s | 4913.4 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 1 | avgt | 420.71 | - | ns/op | 1280.3 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 1 | thrpt | 2421716.47 | - | ops/s | 1280.3 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 4 | avgt | 517.31 | - | ns/op | 1280.2 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=10) | 4 | thrpt | 7704818.11 | - | ops/s | 1280.2 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 1 | avgt | 426.97 | - | ns/op | 1248.3 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 1 | thrpt | 2495917.89 | - | ops/s | 1224.3 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 4 | avgt | 424.63 | - | ns/op | 1248.2 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=100) | 4 | thrpt | 7534757.16 | - | ops/s | 1312.2 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 1 | avgt | 410.85 | - | ns/op | 1248.3 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 1 | thrpt | 2451612.78 | - | ops/s | 1280.3 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 4 | avgt | 432.37 | - | ns/op | 1280.2 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=50) | 4 | thrpt | 7730475.46 | - | ops/s | 1280.2 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 1 | avgt | 415.62 | - | ns/op | 1248.3 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 1 | thrpt | 2415371.95 | - | ops/s | 1280.3 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 4 | avgt | 430.62 | - | ns/op | 1280.2 B/op |
| Component | AggregateLoadComponentBenchmark.loadEmptyStateAggregate (eventCount=500) | 4 | thrpt | 7802968.26 | - | ops/s | 1280.2 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 1 | avgt | 1.40 | - | us/op | 4929.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 1 | thrpt | 729359.24 | - | ops/s | 5008.9 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 4 | avgt | 1.84 | - | us/op | 5024.8 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=10) | 4 | thrpt | 1510949.61 | - | ops/s | 5009.2 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 1 | avgt | 1.63 | - | us/op | 4929.1 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 1 | thrpt | 720115.57 | - | ops/s | 4929.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 4 | avgt | 1.75 | - | us/op | 5032.8 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=100) | 4 | thrpt | 1511301.18 | - | ops/s | 5009.2 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 1 | avgt | 1.36 | - | us/op | 4929.0 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 1 | thrpt | 730185.34 | - | ops/s | 5008.9 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 4 | avgt | 1.96 | - | us/op | 5008.9 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=50) | 4 | thrpt | 1556467.79 | - | ops/s | 5033.2 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 1 | avgt | 2.13 | - | us/op | 4929.5 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 1 | thrpt | 744799.61 | - | ops/s | 5008.9 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 4 | avgt | 2.02 | - | us/op | 5008.9 B/op |
| Component | AggregateLoadComponentBenchmark.loadSnapshot (eventCount=500) | 4 | thrpt | 1553315.90 | - | ops/s | 5049.2 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 1 | avgt | 1.83 | - | us/op | 2153.2 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 1 | thrpt | 438283.82 | - | ops/s | 2153.5 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 4 | avgt | 2.34 | - | us/op | 2153.0 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=10) | 4 | thrpt | 2199547.55 | - | ops/s | 2152.8 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 1 | avgt | 21.14 | - | us/op | 52902.4 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 1 | thrpt | 40783.08 | - | ops/s | 52904.5 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 4 | avgt | 25.80 | - | us/op | 52923.1 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=100) | 4 | thrpt | 180821.49 | - | ops/s | 52921.5 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 1 | avgt | 9.67 | - | us/op | 16694.5 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 1 | thrpt | 83177.64 | - | ops/s | 16720.1 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 4 | avgt | 12.10 | - | us/op | 16693.2 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=50) | 4 | thrpt | 407456.27 | - | ops/s | 16716.2 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 1 | avgt | 182.83 | - | us/op | 1062612.7 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 1 | thrpt | 5929.78 | - | ops/s | 1062602.6 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 4 | avgt | 248.71 | - | us/op | 1062595.1 B/op |
| Component | AggregateLoadComponentBenchmark.recoverFromEvents (eventCount=500) | 4 | thrpt | 13341.87 | - | ops/s | 1062617.2 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 1 | avgt | 7.11 | - | ns/op | 32.0 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 1 | thrpt | 235247442.67 | - | ops/s | 32.0 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 4 | avgt | 7.24 | - | ns/op | 32.0 B/op |
| Component | CommandIdComponentBenchmark.createAggregateId | 4 | thrpt | 469346948.62 | - | ops/s | 32.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 1 | avgt | 65.66 | - | ns/op | 240.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 1 | thrpt | 26316486.82 | - | ops/s | 240.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 4 | avgt | 482.04 | - | ns/op | 240.2 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalId | 4 | thrpt | 7033585.24 | - | ops/s | 240.2 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 1 | avgt | 48.29 | - | ns/op | 272.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 1 | thrpt | 21603907.02 | - | ops/s | 272.0 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 4 | avgt | 516.74 | - | ns/op | 272.2 B/op |
| Component | CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId | 4 | thrpt | 6659299.29 | - | ops/s | 272.3 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 1 | avgt | 19.04 | - | ns/op | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 1 | thrpt | 51737380.10 | - | ops/s | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 4 | avgt | 51.25 | - | ns/op | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createAndMutateHeader | 4 | thrpt | 67534304.21 | - | ops/s | 240.0 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 1 | avgt | 201.93 | - | ns/op | 1080.1 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 1 | thrpt | 5030960.07 | - | ops/s | 1080.1 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 4 | avgt | 1.74 | - | us/op | 1080.8 B/op |
| Component | CommandMessageComponentBenchmark.createCommandMessage | 4 | thrpt | 2155067.79 | - | ops/s | 1080.8 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 1 | avgt | 10.47 | - | ns/op | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 1 | thrpt | 143542245.36 | - | ops/s | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 4 | avgt | 16.78 | - | ns/op | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.createEmptyHeader | 4 | thrpt | 217620556.64 | - | ops/s | 80.0 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 1 | avgt | 294.35 | - | ns/op | 1080.2 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 1 | thrpt | 4993809.65 | - | ops/s | 1080.1 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 4 | avgt | 1.72 | - | us/op | 1080.8 B/op |
| Component | CommandMessageComponentBenchmark.readCommandMessageProperties | 4 | thrpt | 2177441.52 | - | ops/s | 1080.8 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 1 | avgt | 7.37 | - | us/op | 10526.5 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 1 | thrpt | 213117.94 | - | ops/s | 10500.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 4 | avgt | 5.46 | - | us/op | 10523.2 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait | 4 | thrpt | 493788.79 | - | ops/s | 10541.2 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 1 | avgt | 5.93 | - | us/op | 8853.2 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 1 | thrpt | 274053.18 | - | ops/s | 8851.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 4 | avgt | 4.36 | - | us/op | 8874.5 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait | 4 | thrpt | 640334.41 | - | ops/s | 8875.9 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 1 | avgt | 5.14 | - | us/op | 7604.4 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 1 | thrpt | 317723.28 | - | ops/s | 7602.6 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 4 | avgt | 3.56 | - | us/op | 7641.9 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent | 4 | thrpt | 710876.91 | - | ops/s | 7643.4 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 1 | avgt | 5.95 | - | us/op | 8821.2 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 1 | thrpt | 271851.00 | - | ops/s | 8819.2 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 4 | avgt | 4.21 | - | us/op | 8858.4 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents | 4 | thrpt | 627266.87 | - | ops/s | 8860.0 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 1 | avgt | 4.70 | - | us/op | 7307.9 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 1 | thrpt | 326659.00 | - | ops/s | 7330.5 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 4 | avgt | 3.56 | - | us/op | 7345.9 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateOnly | 4 | thrpt | 770153.12 | - | ops/s | 7371.0 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 1 | avgt | 4.25 | - | us/op | 6219.6 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 1 | thrpt | 398218.19 | - | ops/s | 6242.1 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 4 | avgt | 3.01 | - | us/op | 6257.6 B/op |
| Component | CommandPipelineComponentBenchmark.handleAggregateWithoutRetry | 4 | thrpt | 885441.91 | - | ops/s | 6234.6 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 1 | avgt | 4.92 | - | us/op | 10641.5 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 1 | thrpt | 319831.94 | - | ops/s | 10625.8 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 4 | avgt | 6.12 | - | us/op | 10542.5 B/op |
| Component | CommandPipelineComponentBenchmark.sendCommandFireAndForget | 4 | thrpt | 550559.07 | - | ops/s | 10571.0 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 1 | avgt | 399.99 | - | ns/op | 1088.3 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 1 | thrpt | 2522816.01 | - | ops/s | 1088.3 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 4 | avgt | 403.33 | - | ns/op | 1088.2 B/op |
| Component | CommandValidationComponentBenchmark.validateCommand | 4 | thrpt | 9143554.08 | - | ops/s | 1088.2 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 1 | avgt | 114.28 | - | ns/op | 672.1 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 1 | thrpt | 14441452.10 | - | ops/s | 672.0 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 4 | avgt | 145.00 | - | ns/op | 672.1 B/op |
| Component | EventPublishComponentBenchmark.copyStateEvent | 4 | thrpt | 21288905.91 | - | ops/s | 672.1 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 1 | avgt | 52.19 | - | ns/op | 48.0 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 1 | thrpt | 25420437.91 | - | ops/s | 48.0 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 4 | avgt | 40.14 | - | ns/op | 48.0 B/op |
| Component | EventPublishComponentBenchmark.lookupEventFunction | 4 | thrpt | 81109401.15 | - | ops/s | 48.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 1 | avgt | 109.28 | - | ns/op | 168.1 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 1 | thrpt | 13532956.71 | - | ops/s | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 4 | avgt | 85.58 | - | ns/op | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishDomainEventStream | 4 | thrpt | 33574956.72 | - | ops/s | 168.1 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 1 | avgt | 112.70 | - | ns/op | 168.1 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 1 | thrpt | 13185690.68 | - | ops/s | 168.1 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 4 | avgt | 87.26 | - | ns/op | 168.0 B/op |
| Component | EventPublishComponentBenchmark.publishStateEvent | 4 | thrpt | 32470165.90 | - | ops/s | 168.1 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 1 | avgt | 729.48 | - | ns/op | 2640.7 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 1 | thrpt | 2069650.60 | - | ops/s | 2640.5 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 4 | avgt | 2.37 | - | us/op | 2673.8 B/op |
| Component | EventStoreComponentBenchmark.appendInMemoryEventStream | 4 | thrpt | 1579345.16 | - | ops/s | 2649.6 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 1 | avgt | 540.71 | - | ns/op | 2112.3 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 1 | thrpt | 2973211.99 | - | ops/s | 2112.2 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 4 | avgt | 2.29 | - | us/op | 2113.0 B/op |
| Component | EventStoreComponentBenchmark.appendNoopEventStream | 4 | thrpt | 1647125.92 | - | ops/s | 2113.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 1 | avgt | 4.67 | - | us/op | 7291.2 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 1 | thrpt | 359062.30 | - | ops/s | 7289.9 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 4 | avgt | 3.64 | - | us/op | 7337.6 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamByJsonRoundTrip | 4 | thrpt | 769441.54 | - | ops/s | 7290.4 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 1 | avgt | 106.38 | - | ns/op | 632.1 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 1 | thrpt | 15028641.12 | - | ops/s | 632.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 4 | avgt | 137.05 | - | ns/op | 632.0 B/op |
| Component | EventStoreComponentBenchmark.copyEventStreamWithDomainCopy | 4 | thrpt | 24515969.45 | - | ops/s | 632.1 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 1 | avgt | 547.93 | - | ns/op | 2176.4 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 1 | thrpt | 2715589.50 | - | ops/s | 2176.2 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 4 | avgt | 2.45 | - | us/op | 2177.0 B/op |
| Component | EventStoreComponentBenchmark.createEventStream | 4 | thrpt | 1760998.74 | - | ops/s | 2176.9 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 1 | avgt | 844.59 | - | ns/op | 897.0 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 1 | thrpt | 1556040.98 | - | ops/s | 870.6 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 4 | avgt | 939.09 | - | ns/op | 896.9 B/op |
| Component | IdempotencyComponentBenchmark.checkBloomFilterRequestId | 4 | thrpt | 3377932.23 | - | ops/s | 862.7 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 1 | avgt | 212.04 | - | ns/op | 472.2 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 1 | thrpt | 6253337.10 | - | ops/s | 472.1 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 4 | avgt | 165.62 | - | ns/op | 472.1 B/op |
| Component | IdempotencyComponentBenchmark.checkKnownRequestId | 4 | thrpt | 18777988.52 | - | ops/s | 472.1 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 1 | avgt | 938.76 | - | ns/op | 861.2 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 1 | thrpt | 1656414.24 | - | ops/s | 908.8 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 4 | avgt | 915.45 | - | ns/op | 894.9 B/op |
| Component | IdempotencyComponentBenchmark.checkNewRequestId | 4 | thrpt | 3559401.18 | - | ops/s | 875.7 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 1 | avgt | 2.26 | - | us/op | 4225.5 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 1 | thrpt | 661003.30 | - | ops/s | 4225.0 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 4 | avgt | 2.05 | - | us/op | 4248.9 B/op |
| Component | SerializationComponentBenchmark.commandDeserialize | 4 | thrpt | 1388738.39 | - | ops/s | 4249.3 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 1 | avgt | 765.33 | - | ns/op | 1000.6 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 1 | thrpt | 2076786.44 | - | ops/s | 1000.4 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 4 | avgt | 992.51 | - | ns/op | 1032.4 B/op |
| Component | SerializationComponentBenchmark.commandSerialize | 4 | thrpt | 3307240.92 | - | ops/s | 1000.5 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 1 | avgt | 3.30 | - | us/op | 5226.2 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 1 | thrpt | 466274.06 | - | ops/s | 5225.5 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 4 | avgt | 3.14 | - | us/op | 5225.3 B/op |
| Component | SerializationComponentBenchmark.commandSerializeDeserialize | 4 | thrpt | 947248.73 | - | ops/s | 5225.9 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 1 | avgt | 409.18 | - | ns/op | 1072.3 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 1 | thrpt | 3489752.20 | - | ops/s | 1072.2 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 4 | avgt | 879.59 | - | ns/op | 1096.4 B/op |
| Component | SerializationComponentBenchmark.deserializePayload | 4 | thrpt | 4002406.09 | - | ops/s | 1096.4 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 1 | avgt | 3.19 | - | us/op | 6050.2 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 1 | thrpt | 499329.07 | - | ops/s | 6049.4 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 4 | avgt | 2.65 | - | us/op | 6073.2 B/op |
| Component | SerializationComponentBenchmark.eventStreamDeserialize | 4 | thrpt | 1098109.09 | - | ops/s | 6073.7 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 1 | avgt | 973.52 | - | ns/op | 1240.8 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 1 | thrpt | 1573057.04 | - | ops/s | 1240.5 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 4 | avgt | 1.06 | - | us/op | 1240.4 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerialize | 4 | thrpt | 2873312.29 | - | ops/s | 1240.6 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 1 | avgt | 4.72 | - | us/op | 7339.3 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 1 | thrpt | 355245.16 | - | ops/s | 7289.9 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 4 | avgt | 3.86 | - | us/op | 7313.7 B/op |
| Component | SerializationComponentBenchmark.eventStreamSerializeDeserialize | 4 | thrpt | 749533.44 | - | ops/s | 7322.5 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 1 | avgt | 645.44 | - | ns/op | 1616.4 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 1 | thrpt | 2262167.15 | - | ops/s | 1616.3 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 4 | avgt | 1.72 | - | us/op | 1616.7 B/op |
| Component | SerializationComponentBenchmark.roundTripPayload | 4 | thrpt | 2099426.54 | - | ops/s | 1616.8 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 1 | avgt | 192.21 | - | ns/op | 544.1 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 1 | thrpt | 8284721.71 | - | ops/s | 544.1 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 4 | avgt | 807.23 | - | ns/op | 544.3 B/op |
| Component | SerializationComponentBenchmark.serializePayload | 4 | thrpt | 4526878.13 | - | ops/s | 544.4 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 1 | avgt | 192.48 | - | ns/op | 544.1 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 1 | thrpt | 8447416.71 | - | ops/s | 544.1 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 4 | avgt | 837.35 | - | ns/op | 544.4 B/op |
| Component | SerializationComponentBenchmark.serializePayloadWithSharedMapper | 4 | thrpt | 4521169.97 | - | ops/s | 544.4 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 1 | avgt | 448.60 | - | ns/op | 1296.3 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 1 | thrpt | 3192881.84 | - | ops/s | 1296.2 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 4 | avgt | 1.30 | - | us/op | 1296.6 B/op |
| Component | WaitNotifyComponentBenchmark.notifyProcessed | 4 | thrpt | 3333792.06 | - | ops/s | 1280.5 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitStrategy | 1 | avgt | 134.40 | - | ns/op | 408.1 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitStrategy | 1 | thrpt | 11582418.54 | - | ops/s | 408.1 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitStrategy | 4 | avgt | 579.31 | - | ns/op | 408.3 B/op |
| Component | WaitNotifyComponentBenchmark.registerWaitStrategy | 4 | thrpt | 6833594.28 | - | ops/s | 408.3 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 1 | avgt | 644.44 | - | ns/op | 1616.4 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 1 | thrpt | 2011962.79 | - | ops/s | 1616.3 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 4 | avgt | 1.13 | - | us/op | 1660.7 B/op |
| Component | WaitNotifyComponentBenchmark.waitForProcessed | 4 | thrpt | 3557846.12 | - | ops/s | 1632.5 B/op |


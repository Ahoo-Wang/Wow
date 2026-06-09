# Quick Grouped Benchmark Report

## Policy
- Quick results are directional feedback; run Full E2E before updating baselines or claiming framework performance conclusions.
- Framework E2E results isolate command pipeline overhead with in-memory or noop stores; they are not production persistence capacity.
- Infrastructure E2E results reflect real Redis or Mongo persistence paths when services are available.
- No-snapshot growing-stream scenarios are diagnostics for replay pressure, not default E2E goals.
- Component results explain bottlenecks and are not standalone performance goals.
- Smoke results are excluded from performance reports.

## Environment
- **Version**: 8.4.0
- **JVM**: OpenJDK 64-Bit Server VM 17.0.7+7-LTS
- **OS**: Mac OS X 26.5.1 aarch64
- **DateTime**: 2026-06-09T21:33:06+08:00
- **CPU Cores**: 14
- **Physical Memory**: 24.0 GiB
- **Benchmark JVM Args**: `-Xmx4g -Xms4g -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+AlwaysPreTouch`
- **JMH Config**: warmup=1x3s, measurement=2x5s, fork=1, threads=1,4, modes=thrpt,avgt

## Framework E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 79724.21 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 86559.24 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 91517.19 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 150785.63 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 161009.29 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 163224.70 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 222061.10 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 233019.84 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 244539.84 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 256516.40 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 7050.6 B/op | - | 86559.24 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 7001.6 B/op | - | 256516.40 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 6924.4 B/op | - | 222061.10 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 6858.3 B/op | - | 233019.84 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 6828.0 B/op | - | 161009.29 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 6819.0 B/op | - | 79724.21 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 6750.8 B/op | - | 150785.63 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 6744.4 B/op | - | 244539.84 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | thrpt | 5363.1 B/op | - | 270405.15 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 5289.5 B/op | - | 91517.19 | ops/s |

## Infrastructure E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 2313.02 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 3490.08 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 7433.05 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 12004.46 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitSent | 382362.85 | - | ops/s |
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitSent | 422953.17 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitSent | 434055.49 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitSent | 527518.16 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 43362.5 B/op | - | 2313.02 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 41004.9 B/op | - | 7433.05 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 8620.6 B/op | - | 3490.08 | ops/s |
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitSent | thrpt | 7911.6 B/op | - | 422953.17 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitSent | thrpt | 7910.3 B/op | - | 382362.85 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 7231.3 B/op | - | 12004.46 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitSent | thrpt | 6525.3 B/op | - | 527518.16 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitSent | thrpt | 6515.8 B/op | - | 434055.49 | ops/s |

## Group Details

### Primary Framework E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 79724.21 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 86559.24 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 91517.19 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 150785.63 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 161009.29 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 163224.70 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 222061.10 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 233019.84 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 244539.84 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 256516.40 | - | ops/s |

### Primary Framework E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 7050.6 B/op | - | 86559.24 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 7001.6 B/op | - | 256516.40 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 6924.4 B/op | - | 222061.10 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 6858.3 B/op | - | 233019.84 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 6828.0 B/op | - | 161009.29 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 6819.0 B/op | - | 79724.21 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 6750.8 B/op | - | 150785.63 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 6744.4 B/op | - | 244539.84 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | thrpt | 5363.1 B/op | - | 270405.15 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 5289.5 B/op | - | 91517.19 | ops/s |

### Infrastructure E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 2313.02 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 3490.08 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 7433.05 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 12004.46 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitSent | 382362.85 | - | ops/s |
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitSent | 422953.17 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitSent | 434055.49 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitSent | 527518.16 | - | ops/s |

### Infrastructure E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 43362.5 B/op | - | 2313.02 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 41004.9 B/op | - | 7433.05 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 8620.6 B/op | - | 3490.08 | ops/s |
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitSent | thrpt | 7911.6 B/op | - | 422953.17 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitSent | thrpt | 7910.3 B/op | - | 382362.85 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 7231.3 B/op | - | 12004.46 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitSent | thrpt | 6525.3 B/op | - | 527518.16 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitSent | thrpt | 6515.8 B/op | - | 434055.49 | ops/s |

## Primary Framework E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickE2E`
- **Performance Conclusion Source**: no
- **Source Row Count**: 24
- **Parsed Row Count**: 24

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-1-framework-e2e.json`
  - Last Modified: 2026-06-09T13:17:34.690Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-4-framework-e2e.json`
  - Last Modified: 2026-06-09T13:20:23.888Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | avgt | 10.79 | - | us/op | 5293.8 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | thrpt | 91517.19 | - | ops/s | 5289.5 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 4 | avgt | 24.41 | - | us/op | 5279.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 4 | thrpt | 163224.70 | - | ops/s | 5279.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | avgt | 12.58 | - | us/op | 6687.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | thrpt | 79724.21 | - | ops/s | 6819.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 4 | avgt | 26.76 | - | us/op | 6702.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 4 | thrpt | 150785.63 | - | ops/s | 6750.8 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | avgt | 11.92 | - | us/op | 6946.8 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | thrpt | 86559.24 | - | ops/s | 7050.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 4 | avgt | 24.97 | - | us/op | 7004.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 4 | thrpt | 161009.29 | - | ops/s | 6828.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | avgt | 1.93 | - | us/op | 5207.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | thrpt | 512207.06 | - | ops/s | 5204.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 4 | avgt | 15.22 | - | us/op | 5374.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 4 | thrpt | 270405.15 | - | ops/s | 5363.1 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | avgt | 4.38 | - | us/op | 6879.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | thrpt | 233019.84 | - | ops/s | 6858.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 4 | avgt | 16.79 | - | us/op | 6702.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 4 | thrpt | 244539.84 | - | ops/s | 6744.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | avgt | 4.00 | - | us/op | 7103.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | thrpt | 256516.40 | - | ops/s | 7001.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 4 | avgt | 17.93 | - | us/op | 7034.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 4 | thrpt | 222061.10 | - | ops/s | 6924.4 B/op |

## Infrastructure E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E`
- **Performance Conclusion Source**: no
- **Source Row Count**: 16
- **Parsed Row Count**: 16

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-1-infrastructure-e2e.json`
  - Last Modified: 2026-06-09T13:30:54.732Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-4-infrastructure-e2e.json`
  - Last Modified: 2026-06-09T13:32:57.146Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | avgt | 395.03 | - | us/op | 42998.0 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | thrpt | 2313.02 | - | ops/s | 43362.5 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | avgt | 515.07 | - | us/op | 40570.4 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | thrpt | 7433.05 | - | ops/s | 41004.9 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitSent | 1 | avgt | 2.40 | - | us/op | 8011.5 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitSent | 1 | thrpt | 422953.17 | - | ops/s | 7911.6 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitSent | 4 | avgt | 10.87 | - | us/op | 7902.9 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitSent | 4 | thrpt | 382362.85 | - | ops/s | 7910.3 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | avgt | 262.00 | - | us/op | 8481.4 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | thrpt | 3490.08 | - | ops/s | 8620.6 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | avgt | 336.63 | - | us/op | 7165.4 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | thrpt | 12004.46 | - | ops/s | 7231.3 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitSent | 1 | avgt | 2.26 | - | us/op | 6475.3 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitSent | 1 | thrpt | 434055.49 | - | ops/s | 6515.8 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitSent | 4 | avgt | 7.18 | - | us/op | 6581.0 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitSent | 4 | thrpt | 527518.16 | - | ops/s | 6525.3 B/op |

## Component Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickComponent`
- **Performance Conclusion Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-1-component.json`
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-4-component.json`

Status: unavailable. Result files were not present. Run benchmarkQuickComponent to include this optional group.


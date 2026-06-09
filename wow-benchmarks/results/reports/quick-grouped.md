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
- **DateTime**: 2026-06-09T22:01:58+08:00
- **CPU Cores**: 14
- **Physical Memory**: 24.0 GiB
- **Benchmark JVM Args**: `-Xmx4g -Xms4g -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+AlwaysPreTouch`
- **JMH Config**: warmup=1x3s, measurement=2x5s, fork=1, threads=1,4, modes=thrpt,avgt

## Framework E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 78993.02 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 86516.74 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 93867.83 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 149550.09 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 160193.04 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 163467.67 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 756563.75 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 820482.15 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 966652.09 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1155402.19 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 6940.7 B/op | - | 160193.04 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 6920.2 B/op | - | 86516.74 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 6726.7 B/op | - | 149550.09 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 6688.3 B/op | - | 78993.02 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 6254.7 B/op | - | 1155402.19 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 6188.5 B/op | - | 756563.75 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 5964.2 B/op | - | 820482.15 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 5951.5 B/op | - | 1282237.95 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 5297.9 B/op | - | 93867.83 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 5279.3 B/op | - | 163467.67 | ops/s |

## Infrastructure E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 2412.71 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 3619.86 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 7427.19 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 11853.65 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 43170.8 B/op | - | 2412.71 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 40884.5 B/op | - | 7427.19 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 8629.5 B/op | - | 3619.86 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 7188.5 B/op | - | 11853.65 | ops/s |

## Group Details

### Primary Framework E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 78993.02 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 86516.74 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 93867.83 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 149550.09 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 160193.04 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 163467.67 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 756563.75 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 820482.15 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 966652.09 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1155402.19 | - | ops/s |

### Primary Framework E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 6940.7 B/op | - | 160193.04 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 6920.2 B/op | - | 86516.74 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 6726.7 B/op | - | 149550.09 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 6688.3 B/op | - | 78993.02 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 6254.7 B/op | - | 1155402.19 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 6188.5 B/op | - | 756563.75 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 5964.2 B/op | - | 820482.15 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 5951.5 B/op | - | 1282237.95 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 5297.9 B/op | - | 93867.83 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 5279.3 B/op | - | 163467.67 | ops/s |

### Infrastructure E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 2412.71 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 3619.86 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 7427.19 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 11853.65 | - | ops/s |

### Infrastructure E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 43170.8 B/op | - | 2412.71 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 40884.5 B/op | - | 7427.19 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 8629.5 B/op | - | 3619.86 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 7188.5 B/op | - | 11853.65 | ops/s |

## Primary Framework E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickE2E`
- **Performance Conclusion Source**: no
- **Source Row Count**: 24
- **Parsed Row Count**: 24

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-1-framework-e2e.json`
  - Last Modified: 2026-06-09T13:56:48.567Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-4-framework-e2e.json`
  - Last Modified: 2026-06-09T13:59:40.993Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | avgt | 10.98 | - | us/op | 5298.5 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | thrpt | 93867.83 | - | ops/s | 5297.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 4 | avgt | 24.49 | - | us/op | 5303.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 4 | thrpt | 163467.67 | - | ops/s | 5279.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | avgt | 12.68 | - | us/op | 6712.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | thrpt | 78993.02 | - | ops/s | 6688.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 4 | avgt | 26.39 | - | us/op | 6726.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 4 | thrpt | 149550.09 | - | ops/s | 6726.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | avgt | 11.89 | - | us/op | 6946.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | thrpt | 86516.74 | - | ops/s | 6920.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 4 | avgt | 25.18 | - | us/op | 6829.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 4 | thrpt | 160193.04 | - | ops/s | 6940.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | avgt | 1.04 | - | us/op | 5180.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | thrpt | 966652.09 | - | ops/s | 5180.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 4 | avgt | 3.20 | - | us/op | 5182.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 4 | thrpt | 1180727.95 | - | ops/s | 5182.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | avgt | 1.26 | - | us/op | 5948.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | thrpt | 820482.15 | - | ops/s | 5964.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 4 | avgt | 3.25 | - | us/op | 5951.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 4 | thrpt | 1282237.95 | - | ops/s | 5951.5 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | avgt | 1.25 | - | us/op | 6140.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | thrpt | 756563.75 | - | ops/s | 6188.5 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 4 | avgt | 3.20 | - | us/op | 6190.5 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 4 | thrpt | 1155402.19 | - | ops/s | 6254.7 B/op |

## Infrastructure E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E`
- **Performance Conclusion Source**: no
- **Source Row Count**: 8
- **Parsed Row Count**: 8

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-1-infrastructure-e2e.json`
  - Last Modified: 2026-06-09T14:00:51.567Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-4-infrastructure-e2e.json`
  - Last Modified: 2026-06-09T14:01:52.449Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | avgt | 415.17 | - | us/op | 43273.0 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | thrpt | 2412.71 | - | ops/s | 43170.8 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | avgt | 512.21 | - | us/op | 40562.3 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | thrpt | 7427.19 | - | ops/s | 40884.5 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | avgt | 278.20 | - | us/op | 8635.8 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | thrpt | 3619.86 | - | ops/s | 8629.5 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | avgt | 336.63 | - | us/op | 7160.1 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | thrpt | 11853.65 | - | ops/s | 7188.5 B/op |

## Component Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickComponent`
- **Performance Conclusion Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-1-component.json`
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-4-component.json`

Status: unavailable. Result files were not present. Run benchmarkQuickComponent to include this optional group.


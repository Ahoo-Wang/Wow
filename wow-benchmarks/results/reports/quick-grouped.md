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
- **DateTime**: 2026-06-14T07:17:54+08:00
- **CPU Cores**: 14
- **Physical Memory**: 24.0 GiB
- **Benchmark JVM Args**: `-Xmx4g -Xms4g -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+AlwaysPreTouch`
- **JMH Config**: warmup=1x3s, measurement=2x5s, fork=1, threads=1,4, modes=thrpt,avgt

## Framework E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 75929.67 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 77241.18 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 83146.29 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 148235.13 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 161370.64 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 164696.13 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1055378.98 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1220815.66 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1232346.94 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1363363.05 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5315.9 B/op | - | 75929.67 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5315.8 B/op | - | 161370.64 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5130.1 B/op | - | 77241.18 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5007.1 B/op | - | 148235.13 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3718.5 B/op | - | 164696.13 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3715.6 B/op | - | 83146.29 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3262.2 B/op | - | 1363363.05 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3260.2 B/op | - | 1055378.98 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3022.4 B/op | - | 1695841.49 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3020.2 B/op | - | 1220815.66 | ops/s |

## Infrastructure E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 2487.45 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 3787.72 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 7487.35 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 11936.06 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 41383.9 B/op | - | 2487.45 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 38876.7 B/op | - | 7487.35 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 6924.6 B/op | - | 3787.72 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 5594.5 B/op | - | 11936.06 | ops/s |

## Group Details

### Primary Framework E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 75929.67 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 77241.18 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 83146.29 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 148235.13 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 161370.64 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 164696.13 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1055378.98 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1220815.66 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1232346.94 | - | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1363363.05 | - | ops/s |

### Primary Framework E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5315.9 B/op | - | 75929.67 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5315.8 B/op | - | 161370.64 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5130.1 B/op | - | 77241.18 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5007.1 B/op | - | 148235.13 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3718.5 B/op | - | 164696.13 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3715.6 B/op | - | 83146.29 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3262.2 B/op | - | 1363363.05 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3260.2 B/op | - | 1055378.98 | ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3022.4 B/op | - | 1695841.49 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3020.2 B/op | - | 1220815.66 | ops/s |

### Infrastructure E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 2487.45 | - | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 3787.72 | - | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 7487.35 | - | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 11936.06 | - | ops/s |

### Infrastructure E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Infrastructure E2E | 1 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 41383.9 B/op | - | 2487.45 | ops/s |
| Infrastructure E2E | 4 | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 38876.7 B/op | - | 7487.35 | ops/s |
| Infrastructure E2E | 1 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 6924.6 B/op | - | 3787.72 | ops/s |
| Infrastructure E2E | 4 | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | thrpt | 5594.5 B/op | - | 11936.06 | ops/s |

## Primary Framework E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickE2E`
- **Performance Conclusion Source**: no
- **Source Row Count**: 24
- **Parsed Row Count**: 24

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-1-framework-e2e.json`
  - Last Modified: 2026-06-13T23:12:59.374Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-4-framework-e2e.json`
  - Last Modified: 2026-06-13T23:15:51.647Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | avgt | 11.17 | - | us/op | 3714.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | thrpt | 83146.29 | - | ops/s | 3715.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 4 | avgt | 24.28 | - | us/op | 3734.6 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 4 | thrpt | 164696.13 | - | ops/s | 3718.5 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | avgt | 12.76 | - | us/op | 5128.7 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | thrpt | 77241.18 | - | ops/s | 5130.1 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 4 | avgt | 27.23 | - | us/op | 5079.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 4 | thrpt | 148235.13 | - | ops/s | 5007.1 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | avgt | 12.29 | - | us/op | 5253.5 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | thrpt | 75929.67 | - | ops/s | 5315.9 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 4 | avgt | 24.87 | - | us/op | 5316.5 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 4 | thrpt | 161370.64 | - | ops/s | 5315.8 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | avgt | 652.08 | - | ns/op | 2252.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | thrpt | 1450896.71 | - | ops/s | 2252.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 4 | avgt | 3.31 | - | us/op | 2253.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 4 | thrpt | 1232346.94 | - | ops/s | 2269.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | avgt | 741.86 | - | ns/op | 3020.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | thrpt | 1220815.66 | - | ops/s | 3020.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 4 | avgt | 2.56 | - | us/op | 3054.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 4 | thrpt | 1695841.49 | - | ops/s | 3022.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | avgt | 814.48 | - | ns/op | 3260.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | thrpt | 1055378.98 | - | ops/s | 3260.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 4 | avgt | 3.17 | - | us/op | 3262.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 4 | thrpt | 1363363.05 | - | ops/s | 3262.2 B/op |

## Infrastructure E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E`
- **Performance Conclusion Source**: no
- **Source Row Count**: 8
- **Parsed Row Count**: 8

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-1-infrastructure-e2e.json`
  - Last Modified: 2026-06-13T23:16:52.976Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-4-infrastructure-e2e.json`
  - Last Modified: 2026-06-13T23:17:54.354Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | avgt | 418.05 | - | us/op | 41424.2 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | thrpt | 2487.45 | - | ops/s | 41383.9 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | avgt | 526.03 | - | us/op | 38707.9 B/op |
| Infrastructure E2E | MongoCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | thrpt | 7487.35 | - | ops/s | 38876.7 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | avgt | 271.40 | - | us/op | 6939.5 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 1 | thrpt | 3787.72 | - | ops/s | 6924.6 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | avgt | 335.38 | - | us/op | 5578.5 B/op |
| Infrastructure E2E | RedisCommandWriteE2EBenchmark.sendAndWaitProcessed | 4 | thrpt | 11936.06 | - | ops/s | 5594.5 B/op |

## Component Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickComponent`
- **Performance Conclusion Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-1-component.json`
- **threads=4 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-4-component.json`

Status: unavailable. Result files were not present. Run benchmarkQuickComponent to include this optional group.


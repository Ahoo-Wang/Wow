# Baseline Grouped Benchmark Report

## Policy
- Baseline E2E results are the performance conclusion source.
- Framework E2E results isolate command pipeline overhead with in-memory or noop stores; they are not production persistence capacity.
- Infrastructure E2E results reflect real Redis or Mongo persistence paths when services are available.
- No-snapshot growing-stream scenarios are diagnostics for replay pressure, not default E2E goals.
- Component results explain bottlenecks and are not standalone performance goals.
- Smoke results are excluded from performance reports.

## Reading Values

- Throughput uses decimal prefixes: `k` = 1,000, `M` = 1,000,000, `G` = 1,000,000,000.
- Allocation uses binary prefixes: `KiB` = 1,024 bytes, `MiB` = 1,048,576 bytes.
- Average latency is automatically scaled to `ns/op`, `µs/op`, `ms/op`, or `s/op`.
- `±` is the JMH-reported error. Scaling changes presentation only; calculations keep raw precision.

## Benchmark Run Provenance
- **Source Commit**: `fcedf1090454ff7fdb511f303771d3c663ac9c6e`
- **Source Dirty**: `false`
- **Project Version**: `8.9.0`
- **JMH Jar SHA-256**: `1d5902b9f334f5f771736a2933ad577f157440513808ff2bd5a15179acf3332e`
- **Runtime JVM**: OpenJDK 64-Bit Server VM 17.0.7+7-LTS / Java 17.0.7
- **Runtime OS**: Mac OS X 26.5.2 aarch64
- **CPU Cores**: 14
- **Physical Memory**: 24.0 GiB

| Suite | Profile | Threads | Run ID | Started | Completed | Profilers | Rows | Result SHA-256 |
|-------|---------|---------|--------|---------|-----------|-----------|------|----------------|
| framework-e2e | baseline | 1 | `50c53461-4f94-4684-b165-85937d1f244d` | 2026-07-22T09:55:45.617125Z | 2026-07-22T10:01:39.828092Z | `-prof gc` | 8 | `b06ca50144f805636ed12822f072e1dabe2a43f8ff6ca55736c1d9d06d2dcddf` |
| framework-e2e | baseline | 4 | `50c53461-4f94-4684-b165-85937d1f244d` | 2026-07-22T10:01:39.891081Z | 2026-07-22T10:07:29.113914Z | `-prof gc` | 8 | `3de252b2f8304f1fc7570b26f7d36a17e85f7e9b42c98ea4434701b09f55f08e` |

## Report Generation Environment
- **Version**: 8.9.0
- **JVM**: OpenJDK 64-Bit Server VM 17.0.7+7-LTS
- **OS**: Mac OS X 26.5.2 aarch64
- **Generated At**: 2026-07-22T20:04:20+08:00
- **CPU Cores**: 14
- **Physical Memory**: 24.0 GiB
- **Benchmark JVM Args**: see per-suite Run Profiles below

## Run Profiles

- **Primary Framework E2E**: warmup=2x3s, measurement=3x5s, fork=2, threads=1,4, modes=thrpt, profilers=gc, jvmArgs=`-Xmx4g -Xms4g -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+AlwaysPreTouch`
- **Infrastructure E2E**: warmup=2x5s, measurement=3x10s, fork=2, threads=1,4, modes=thrpt,avgt, profilers=gc, jvmArgs=`-Xmx4g -Xms4g -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+AlwaysPreTouch`
- **Component**: warmup=1x2s, measurement=2x3s, fork=1, threads=1, modes=thrpt, profilers=gc, jvmArgs=`-Xmx1g -Xms1g -XX:+UseG1GC`
- **WebFlux Adapter**: warmup=1x3s, measurement=3x5s, fork=1, threads=1,4, modes=thrpt,avgt, profilers=gc, jvmArgs=`-Xmx4g -Xms4g -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+AlwaysPreTouch`

## Framework E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 60.67 | ±12.98 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 63.21 | ±7.43 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 89.37 | ±2.92 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 116.09 | ±6.35 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 125.39 | ±8.54 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 132.12 | ±37.1 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 136.07 | ±5.15 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 160.28 | ±10.9 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 213.38 | ±31.66 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 222.97 | ±20.21 | k ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13.18 KiB/op | ±0.07 KiB/op | 125.39 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13.13 KiB/op | ±<0.01 KiB/op | 213.38 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13.01 KiB/op | ±0.02 KiB/op | 136.07 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 12.97 KiB/op | ±<0.01 KiB/op | 226.04 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.08 KiB/op | ±<0.01 KiB/op | 222.97 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.06 KiB/op | ±0.05 KiB/op | 294.72 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4.78 KiB/op | ±0.08 KiB/op | 60.67 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4.73 KiB/op | ±0.08 KiB/op | 132.12 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4.49 KiB/op | ±<0.01 KiB/op | 89.37 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4.48 KiB/op | ±<0.01 KiB/op | 116.09 | k ops/s |

## Group Details

### Primary Framework E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 60.67 | ±12.98 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 63.21 | ±7.43 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 89.37 | ±2.92 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 116.09 | ±6.35 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 125.39 | ±8.54 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 132.12 | ±37.1 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 136.07 | ±5.15 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 160.28 | ±10.9 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 213.38 | ±31.66 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 222.97 | ±20.21 | k ops/s |

### Primary Framework E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13.18 KiB/op | ±0.07 KiB/op | 125.39 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13.13 KiB/op | ±<0.01 KiB/op | 213.38 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13.01 KiB/op | ±0.02 KiB/op | 136.07 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 12.97 KiB/op | ±<0.01 KiB/op | 226.04 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.08 KiB/op | ±<0.01 KiB/op | 222.97 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.06 KiB/op | ±0.05 KiB/op | 294.72 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4.78 KiB/op | ±0.08 KiB/op | 60.67 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4.73 KiB/op | ±0.08 KiB/op | 132.12 | k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4.49 KiB/op | ±<0.01 KiB/op | 89.37 | k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4.48 KiB/op | ±<0.01 KiB/op | 116.09 | k ops/s |

## Primary Framework E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkBaselineE2E`
- **JMH Config**: warmup=2x3s, measurement=3x5s, fork=2, threads=1,4, modes=thrpt, profilers=gc
- **Performance Conclusion Source**: yes
- **Source Row Count**: 16
- **Parsed Row Count**: 16

- **threads=1 Result File**: `wow-benchmarks/results/jmh/baseline/framework-e2e/threads-1-framework-e2e.json`
  - Last Modified: 2026-07-22T10:01:39.806Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/baseline/framework-e2e/threads-4-framework-e2e.json`
  - Last Modified: 2026-07-22T10:07:29.094Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 1 | thrpt | 1.48 | ±0.14 | M ops/s | 2.21 KiB/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 4 | thrpt | 1.21 | ±0.06 | M ops/s | 2.21 KiB/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 1 | thrpt | 1.32 | ±0.04 | M ops/s | 2.61 KiB/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 4 | thrpt | 1.28 | ±0.16 | M ops/s | 2.61 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 294.72 | ±12.68 | k ops/s | 12.06 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 222.97 | ±20.21 | k ops/s | 12.08 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 1 | thrpt | 63.21 | ±7.43 | k ops/s | 3.86 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 4 | thrpt | 160.28 | ±10.9 | k ops/s | 3.86 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 213.38 | ±31.66 | k ops/s | 13.13 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 125.39 | ±8.54 | k ops/s | 13.18 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 1 | thrpt | 89.37 | ±2.92 | k ops/s | 4.49 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 4 | thrpt | 116.09 | ±6.35 | k ops/s | 4.48 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 226.04 | ±35.5 | k ops/s | 12.97 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 136.07 | ±5.15 | k ops/s | 13.01 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 1 | thrpt | 60.67 | ±12.98 | k ops/s | 4.78 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 4 | thrpt | 132.12 | ±37.1 | k ops/s | 4.73 KiB/op |

## Infrastructure E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkBaselineInfrastructureE2E`
- **JMH Config**: warmup=2x5s, measurement=3x10s, fork=2, threads=1,4, modes=thrpt,avgt, profilers=gc
- **Performance Conclusion Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: `wow-benchmarks/results/jmh/baseline/infrastructure-e2e/threads-1-infrastructure-e2e.json`
- **threads=4 Result File**: `wow-benchmarks/results/jmh/baseline/infrastructure-e2e/threads-4-infrastructure-e2e.json`

Status: unavailable. Result files were not present. Run benchmarkBaselineInfrastructureE2E to include this optional group.

## Component Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkExhaustiveComponent`
- **JMH Config**: warmup=1x2s, measurement=2x3s, fork=1, threads=1, modes=thrpt, profilers=gc
- **Performance Conclusion Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: `wow-benchmarks/results/jmh/exhaustive/component/threads-1-component.json`

Status: unavailable. Result files were not present. Run benchmarkExhaustiveComponent to include this optional group.

## WebFlux Adapter Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkExhaustiveWebFlux`
- **JMH Config**: warmup=1x3s, measurement=3x5s, fork=1, threads=1,4, modes=thrpt,avgt, profilers=gc
- **Performance Conclusion Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: `wow-benchmarks/results/jmh/exhaustive/webflux/threads-1-webflux.json`
- **threads=4 Result File**: `wow-benchmarks/results/jmh/exhaustive/webflux/threads-4-webflux.json`

Status: unavailable. Result files were not present. Run benchmarkExhaustiveWebFlux to include this optional group.

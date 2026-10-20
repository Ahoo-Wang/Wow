# Baseline Grouped Benchmark Report

## Policy
- Baseline E2E is the formal regression source for its exact synchronous workloads; it is not a production capacity model.
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
- **Source Commit**: `cf562ec93eb9ade8801773f90da1fad0ddfea341`
- **Source Dirty**: `false`
- **Project Version**: `8.9.0`
- **JMH Jar SHA-256**: `264e446f22fa0ae471fed066ff1a0ea82311dff859823be9d8b76883c028dd8b`
- **Runtime JVM**: OpenJDK 64-Bit Server VM 17.0.7+7-LTS / Java 17.0.7
- **Runtime OS**: Mac OS X 26.5.2 aarch64
- **CPU Cores**: 14
- **Physical Memory**: 24.0 GiB

| Suite | Profile | Threads | Run ID | Started | Completed | Profilers | Rows | Result SHA-256 |
|-------|---------|---------|--------|---------|-----------|-----------|------|----------------|
| framework-e2e | baseline | 1 | `ced0639f-b651-49cb-b346-15153c6547b7` | 2026-07-22T13:40:13.693180Z | 2026-07-22T13:46:08.659633Z | `-prof gc` | 8 | `ea302feda3385905d5c479dc00e6d599aad61bc86e664e9054dc3e7a88ccd893` |
| framework-e2e | baseline | 4 | `ced0639f-b651-49cb-b346-15153c6547b7` | 2026-07-22T13:46:08.774352Z | 2026-07-22T13:51:59.560202Z | `-prof gc` | 8 | `afe8e24ef2037e1f992f07e4faf07364ef58e6e81bad4883552ecfd49e195be5` |

## Report Generation Environment
- **Version**: 8.9.0
- **JVM**: OpenJDK 64-Bit Server VM 17.0.7+7-LTS
- **OS**: Mac OS X 26.5.2 aarch64
- **Generated At**: 2026-07-22T21:52:33+08:00
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

| Suite | Threads | Benchmark | Score | Error |
|-------|---------|-----------|-------|-------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 54.79 k ops/s | ±32.98 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 66.59 k ops/s | ±30.76 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 74.16 k ops/s | ±25.24 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 114.64 k ops/s | ±5.66 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 124.28 k ops/s | ±8 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 126.65 k ops/s | ±16.91 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 135.54 k ops/s | ±26.29 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 152.67 k ops/s | ±41.09 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 167.62 k ops/s | ±6.62 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 192.93 k ops/s | ±60.36 k ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13.16 KiB/op | ±<0.01 KiB/op | 135.54 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13.13 KiB/op | ±<0.01 KiB/op | 202.85 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13.02 KiB/op | ±<0.01 KiB/op | 152.67 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 12.99 KiB/op | ±0.09 KiB/op | 192.93 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.12 KiB/op | ±0.12 KiB/op | 301.06 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.11 KiB/op | ±0.02 KiB/op | 167.62 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4.74 KiB/op | ±0.04 KiB/op | 74.16 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4.72 KiB/op | ±0.03 KiB/op | 126.65 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4.54 KiB/op | ±0.09 KiB/op | 54.79 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4.48 KiB/op | ±<0.01 KiB/op | 114.64 k ops/s |

## Group Details

### Primary Framework E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error |
|-------|---------|-----------|-------|-------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 54.79 k ops/s | ±32.98 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 66.59 k ops/s | ±30.76 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 74.16 k ops/s | ±25.24 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 114.64 k ops/s | ±5.66 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 124.28 k ops/s | ±8 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 126.65 k ops/s | ±16.91 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 135.54 k ops/s | ±26.29 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 152.67 k ops/s | ±41.09 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 167.62 k ops/s | ±6.62 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 192.93 k ops/s | ±60.36 k ops/s |

### Primary Framework E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |
|-------|---------|-----------|------|------------|------------------|-------|
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13.16 KiB/op | ±<0.01 KiB/op | 135.54 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | thrpt | 13.13 KiB/op | ±<0.01 KiB/op | 202.85 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 13.02 KiB/op | ±<0.01 KiB/op | 152.67 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | thrpt | 12.99 KiB/op | ±0.09 KiB/op | 192.93 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.12 KiB/op | ±0.12 KiB/op | 301.06 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | thrpt | 12.11 KiB/op | ±0.02 KiB/op | 167.62 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4.74 KiB/op | ±0.04 KiB/op | 74.16 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | thrpt | 4.72 KiB/op | ±0.03 KiB/op | 126.65 k ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4.54 KiB/op | ±0.09 KiB/op | 54.79 k ops/s |
| Primary Framework E2E | 4 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | thrpt | 4.48 KiB/op | ±<0.01 KiB/op | 114.64 k ops/s |

## Primary Framework E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkBaselineE2E`
- **JMH Config**: warmup=2x3s, measurement=3x5s, fork=2, threads=1,4, modes=thrpt, profilers=gc
- **Formal Regression Source**: yes
- **Source Row Count**: 16
- **Parsed Row Count**: 16

- **threads=1 Result File**: `wow-benchmarks/results/jmh/baseline/framework-e2e/threads-1-framework-e2e.json`
  - Last Modified: 2026-07-22T13:46:08.638Z
- **threads=4 Result File**: `wow-benchmarks/results/jmh/baseline/framework-e2e/threads-4-framework-e2e.json`
  - Last Modified: 2026-07-22T13:51:59.537Z

| Suite | Benchmark | Threads | Mode | Score | Error | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|-------------------|
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 1 | thrpt | 1.53 M ops/s | ±0.2 M ops/s | 2.21 KiB/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 4 | thrpt | 1 M ops/s | ±0.22 M ops/s | 2.21 KiB/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 1 | thrpt | 1.3 M ops/s | ±0.44 M ops/s | 2.61 KiB/op |
| Primary Framework E2E | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 4 | thrpt | 962.36 k ops/s | ±111.89 k ops/s | 2.65 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 301.06 k ops/s | ±11.97 k ops/s | 12.12 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 167.62 k ops/s | ±6.62 k ops/s | 12.11 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 1 | thrpt | 66.59 k ops/s | ±30.76 k ops/s | 3.86 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 4 | thrpt | 124.28 k ops/s | ±8 k ops/s | 3.86 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 202.85 k ops/s | ±14.06 k ops/s | 13.13 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 135.54 k ops/s | ±26.29 k ops/s | 13.16 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 1 | thrpt | 54.79 k ops/s | ±32.98 k ops/s | 4.54 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 4 | thrpt | 114.64 k ops/s | ±5.66 k ops/s | 4.48 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 192.93 k ops/s | ±60.36 k ops/s | 12.99 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 152.67 k ops/s | ±41.09 k ops/s | 13.02 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 1 | thrpt | 74.16 k ops/s | ±25.24 k ops/s | 4.74 KiB/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 4 | thrpt | 126.65 k ops/s | ±16.91 k ops/s | 4.72 KiB/op |

## Infrastructure E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkBaselineInfrastructureE2E`
- **JMH Config**: warmup=2x5s, measurement=3x10s, fork=2, threads=1,4, modes=thrpt,avgt, profilers=gc
- **Formal Regression Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: `wow-benchmarks/results/jmh/baseline/infrastructure-e2e/threads-1-infrastructure-e2e.json`
- **threads=4 Result File**: `wow-benchmarks/results/jmh/baseline/infrastructure-e2e/threads-4-infrastructure-e2e.json`

Status: unavailable. Result files were not present. Run benchmarkBaselineInfrastructureE2E to include this optional group.

## Component Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkExhaustiveComponent`
- **JMH Config**: warmup=1x2s, measurement=2x3s, fork=1, threads=1, modes=thrpt, profilers=gc
- **Formal Regression Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: `wow-benchmarks/results/jmh/exhaustive/component/threads-1-component.json`

Status: unavailable. Result files were not present. Run benchmarkExhaustiveComponent to include this optional group.

## WebFlux Adapter Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkExhaustiveWebFlux`
- **JMH Config**: warmup=1x3s, measurement=3x5s, fork=1, threads=1,4, modes=thrpt,avgt, profilers=gc
- **Formal Regression Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: `wow-benchmarks/results/jmh/exhaustive/webflux/threads-1-webflux.json`
- **threads=4 Result File**: `wow-benchmarks/results/jmh/exhaustive/webflux/threads-4-webflux.json`

Status: unavailable. Result files were not present. Run benchmarkExhaustiveWebFlux to include this optional group.

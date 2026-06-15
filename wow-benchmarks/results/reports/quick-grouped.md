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
- **DateTime**: 2026-06-15T15:23:40+08:00
- **CPU Cores**: 14
- **Physical Memory**: 24.0 GiB
- **Benchmark JVM Args**: `-Xmx4g -Xms4g -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+AlwaysPreTouch`
- **JMH Config**: warmup=1x3s, measurement=2x5s, fork=1, threads=1, modes=thrpt,avgt

## Framework E2E Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 57326.38 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 58190.73 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 62996.69 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1219821.64 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1320664.72 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1497539.27 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5528.3 B/op | - | 58190.73 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5346.0 B/op | - | 57326.38 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3959.8 B/op | - | 62996.69 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3260.2 B/op | - | 1219821.64 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3020.2 B/op | - | 1320664.72 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | thrpt | 2268.2 B/op | - | 1497539.27 | ops/s |

## WebFlux Adapter Bottlenecks

### Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.copyCartStateOnly (eventCount=100, traceWindowSize=10) | 1019.13 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1752.89 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.directSerializeTraceCartHistory (eventCount=100, traceWindowSize=10) | 1928.47 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.serializeTracedCartHistoryOnly (eventCount=100, traceWindowSize=10) | 2816.42 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceCartHistory (eventCount=100, traceWindowSize=10) | 4849.17 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.jsonSnapshotCartStateOnly (eventCount=100, traceWindowSize=10) | 5083.28 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.prefixReplayWindowedTraceAndSerialize (eventCount=100, traceWindowSize=10) | 11306.39 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.prefixReplayWindowedTraceCartHistory (eventCount=100, traceWindowSize=10) | 23936.43 | - | ops/s |
| WebFlux Adapter | 1 | WebFluxResponseBenchmark.commandResultSseResponse | 54432.74 | - | ops/s |
| WebFlux Adapter | 1 | CommandHandlerFunctionBenchmark.postAddCartItemWaitSent | 56952.20 | - | ops/s |

### Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.directSerializeTraceCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 3213856.9 B/op | - | 1928.47 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2951520.5 B/op | - | 1752.89 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.copyCartStateOnly (eventCount=100, traceWindowSize=10) | thrpt | 2785131.2 B/op | - | 1019.13 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 1797655.5 B/op | - | 4849.17 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.jsonSnapshotCartStateOnly (eventCount=100, traceWindowSize=10) | thrpt | 1738259.1 B/op | - | 5083.28 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.serializeTracedCartHistoryOnly (eventCount=100, traceWindowSize=10) | thrpt | 1069056.0 B/op | - | 2816.42 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.prefixReplayWindowedTraceAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 442167.9 B/op | - | 11306.39 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.prefixReplayWindowedTraceCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 377888.6 B/op | - | 23936.43 | ops/s |
| WebFlux Adapter | 1 | WebFluxResponseBenchmark.commandResultSseResponse | thrpt | 120448.0 B/op | - | 54432.74 | ops/s |
| WebFlux Adapter | 1 | WebFluxResponseBenchmark.commandResultSseWriteToExchange | thrpt | 100328.0 B/op | - | 81503.81 | ops/s |

## Group Details

### Primary Framework E2E Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 57326.38 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 58190.73 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 62996.69 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1219821.64 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1320664.72 | - | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1497539.27 | - | ops/s |

### Primary Framework E2E Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | thrpt | 5528.3 B/op | - | 58190.73 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | thrpt | 5346.0 B/op | - | 57326.38 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | thrpt | 3959.8 B/op | - | 62996.69 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | thrpt | 3260.2 B/op | - | 1219821.64 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | thrpt | 3020.2 B/op | - | 1320664.72 | ops/s |
| Primary Framework E2E | 1 | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | thrpt | 2268.2 B/op | - | 1497539.27 | ops/s |

### WebFlux Adapter Lowest Throughput

| Suite | Threads | Benchmark | Score | Error | Unit |
|-------|---------|-----------|-------|-------|------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.copyCartStateOnly (eventCount=100, traceWindowSize=10) | 1019.13 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1752.89 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.directSerializeTraceCartHistory (eventCount=100, traceWindowSize=10) | 1928.47 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.serializeTracedCartHistoryOnly (eventCount=100, traceWindowSize=10) | 2816.42 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceCartHistory (eventCount=100, traceWindowSize=10) | 4849.17 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.jsonSnapshotCartStateOnly (eventCount=100, traceWindowSize=10) | 5083.28 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.prefixReplayWindowedTraceAndSerialize (eventCount=100, traceWindowSize=10) | 11306.39 | - | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.prefixReplayWindowedTraceCartHistory (eventCount=100, traceWindowSize=10) | 23936.43 | - | ops/s |
| WebFlux Adapter | 1 | WebFluxResponseBenchmark.commandResultSseResponse | 54432.74 | - | ops/s |
| WebFlux Adapter | 1 | CommandHandlerFunctionBenchmark.postAddCartItemWaitSent | 56952.20 | - | ops/s |

### WebFlux Adapter Highest Allocation

| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |
|-------|---------|-----------|------|------------|-------|-------|------|
| WebFlux Adapter | 1 | AggregateTracingBenchmark.directSerializeTraceCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 3213856.9 B/op | - | 1928.47 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 2951520.5 B/op | - | 1752.89 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.copyCartStateOnly (eventCount=100, traceWindowSize=10) | thrpt | 2785131.2 B/op | - | 1019.13 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.traceCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 1797655.5 B/op | - | 4849.17 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.jsonSnapshotCartStateOnly (eventCount=100, traceWindowSize=10) | thrpt | 1738259.1 B/op | - | 5083.28 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.serializeTracedCartHistoryOnly (eventCount=100, traceWindowSize=10) | thrpt | 1069056.0 B/op | - | 2816.42 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.prefixReplayWindowedTraceAndSerialize (eventCount=100, traceWindowSize=10) | thrpt | 442167.9 B/op | - | 11306.39 | ops/s |
| WebFlux Adapter | 1 | AggregateTracingBenchmark.prefixReplayWindowedTraceCartHistory (eventCount=100, traceWindowSize=10) | thrpt | 377888.6 B/op | - | 23936.43 | ops/s |
| WebFlux Adapter | 1 | WebFluxResponseBenchmark.commandResultSseResponse | thrpt | 120448.0 B/op | - | 54432.74 | ops/s |
| WebFlux Adapter | 1 | WebFluxResponseBenchmark.commandResultSseWriteToExchange | thrpt | 100328.0 B/op | - | 81503.81 | ops/s |

## Primary Framework E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickE2E`
- **Performance Conclusion Source**: no
- **Source Row Count**: 12
- **Parsed Row Count**: 12

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/framework-e2e/threads-1-framework-e2e.json`
  - Last Modified: 2026-06-15T00:10:56.211Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | avgt | 15.54 | - | us/op | 3959.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling) | 1 | thrpt | 62996.69 | - | ops/s | 3959.8 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | avgt | 17.21 | - | us/op | 5344.4 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate) | 1 | thrpt | 57326.38 | - | ops/s | 5346.0 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | avgt | 16.87 | - | us/op | 5582.1 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store) | 1 | thrpt | 58190.73 | - | ops/s | 5528.3 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | avgt | 690.47 | - | ns/op | 2252.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=ceiling) | 1 | thrpt | 1497539.27 | - | ops/s | 2268.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | avgt | 765.72 | - | ns/op | 3020.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=in-memory-new-aggregate) | 1 | thrpt | 1320664.72 | - | ops/s | 3020.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | avgt | 824.11 | - | ns/op | 3260.2 B/op |
| Primary Framework E2E | CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store) | 1 | thrpt | 1219821.64 | - | ops/s | 3260.2 B/op |

## Infrastructure E2E Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E`
- **Performance Conclusion Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/infrastructure-e2e/threads-1-infrastructure-e2e.json`

Status: unavailable. Result files were not present. Run benchmarkQuickInfrastructureE2E to include this optional group.

## Component Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickComponent`
- **Performance Conclusion Source**: no
- **Source Row Count**: 0
- **Parsed Row Count**: 0

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/component/threads-1-component.json`

Status: unavailable. Result files were not present. Run benchmarkQuickComponent to include this optional group.

## WebFlux Adapter Results

- **Command**: `./gradlew :wow-benchmarks:benchmarkQuickWebFlux`
- **Performance Conclusion Source**: no
- **Source Row Count**: 94
- **Parsed Row Count**: 94

- **threads=1 Result File**: `wow-benchmarks/results/jmh/quick/webflux/threads-1-webflux.json`
  - Last Modified: 2026-06-15T07:23:35.089Z

| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |
|-------|-----------|---------|------|-------|-------|------|-------------------|
| WebFlux Adapter | AggregateTracingBenchmark.copyCartStateOnly (eventCount=1, traceWindowSize=10) | 1 | avgt | 486.83 | - | ns/op | 2112.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.copyCartStateOnly (eventCount=1, traceWindowSize=10) | 1 | thrpt | 2051960.33 | - | ops/s | 2112.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.copyCartStateOnly (eventCount=10, traceWindowSize=10) | 1 | avgt | 12.24 | - | us/op | 43632.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.copyCartStateOnly (eventCount=10, traceWindowSize=10) | 1 | thrpt | 82239.76 | - | ops/s | 43632.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.copyCartStateOnly (eventCount=100, traceWindowSize=10) | 1 | avgt | 923.34 | - | us/op | 2785053.4 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.copyCartStateOnly (eventCount=100, traceWindowSize=10) | 1 | thrpt | 1019.13 | - | ops/s | 2785131.2 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.directSerializeTraceCartHistory (eventCount=1, traceWindowSize=10) | 1 | avgt | 928.95 | - | ns/op | 6080.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.directSerializeTraceCartHistory (eventCount=1, traceWindowSize=10) | 1 | thrpt | 1070157.89 | - | ops/s | 6080.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.directSerializeTraceCartHistory (eventCount=10, traceWindowSize=10) | 1 | avgt | 12.86 | - | us/op | 73344.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.directSerializeTraceCartHistory (eventCount=10, traceWindowSize=10) | 1 | thrpt | 76819.67 | - | ops/s | 73344.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.directSerializeTraceCartHistory (eventCount=100, traceWindowSize=10) | 1 | avgt | 519.64 | - | us/op | 3211457.8 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.directSerializeTraceCartHistory (eventCount=100, traceWindowSize=10) | 1 | thrpt | 1928.47 | - | ops/s | 3213856.9 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.jsonSnapshotCartStateOnly (eventCount=1, traceWindowSize=10) | 1 | avgt | 119.73 | - | ns/op | 1000.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.jsonSnapshotCartStateOnly (eventCount=1, traceWindowSize=10) | 1 | thrpt | 9691648.58 | - | ops/s | 1000.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.jsonSnapshotCartStateOnly (eventCount=10, traceWindowSize=10) | 1 | avgt | 2.58 | - | us/op | 23280.1 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.jsonSnapshotCartStateOnly (eventCount=10, traceWindowSize=10) | 1 | thrpt | 368006.43 | - | ops/s | 23280.1 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.jsonSnapshotCartStateOnly (eventCount=100, traceWindowSize=10) | 1 | avgt | 208.44 | - | us/op | 1738275.1 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.jsonSnapshotCartStateOnly (eventCount=100, traceWindowSize=10) | 1 | thrpt | 5083.28 | - | ops/s | 1738259.1 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.jsonSnapshotStateEventCreationOnly (eventCount=1, traceWindowSize=10) | 1 | avgt | 6.28 | - | ns/op | 88.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.jsonSnapshotStateEventCreationOnly (eventCount=1, traceWindowSize=10) | 1 | thrpt | 153440861.04 | - | ops/s | 88.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.jsonSnapshotStateEventCreationOnly (eventCount=10, traceWindowSize=10) | 1 | avgt | 42.20 | - | ns/op | 480.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.jsonSnapshotStateEventCreationOnly (eventCount=10, traceWindowSize=10) | 1 | thrpt | 30784356.46 | - | ops/s | 480.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.jsonSnapshotStateEventCreationOnly (eventCount=100, traceWindowSize=10) | 1 | avgt | 451.42 | - | ns/op | 4440.6 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.jsonSnapshotStateEventCreationOnly (eventCount=100, traceWindowSize=10) | 1 | thrpt | 2213068.35 | - | ops/s | 4440.6 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.prefixReplayWindowedTraceAndSerialize (eventCount=1, traceWindowSize=10) | 1 | avgt | 1.06 | - | us/op | 3776.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.prefixReplayWindowedTraceAndSerialize (eventCount=1, traceWindowSize=10) | 1 | thrpt | 944171.54 | - | ops/s | 3776.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.prefixReplayWindowedTraceAndSerialize (eventCount=10, traceWindowSize=10) | 1 | avgt | 12.54 | - | us/op | 36952.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.prefixReplayWindowedTraceAndSerialize (eventCount=10, traceWindowSize=10) | 1 | thrpt | 79555.49 | - | ops/s | 36976.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.prefixReplayWindowedTraceAndSerialize (eventCount=100, traceWindowSize=10) | 1 | avgt | 88.32 | - | us/op | 440007.6 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.prefixReplayWindowedTraceAndSerialize (eventCount=100, traceWindowSize=10) | 1 | thrpt | 11306.39 | - | ops/s | 442167.9 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.prefixReplayWindowedTraceCartHistory (eventCount=1, traceWindowSize=10) | 1 | avgt | 243.19 | - | ns/op | 2288.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.prefixReplayWindowedTraceCartHistory (eventCount=1, traceWindowSize=10) | 1 | thrpt | 3956119.27 | - | ops/s | 2288.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.prefixReplayWindowedTraceCartHistory (eventCount=10, traceWindowSize=10) | 1 | avgt | 3.18 | - | us/op | 26408.1 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.prefixReplayWindowedTraceCartHistory (eventCount=10, traceWindowSize=10) | 1 | thrpt | 307211.79 | - | ops/s | 26408.1 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.prefixReplayWindowedTraceCartHistory (eventCount=100, traceWindowSize=10) | 1 | avgt | 46.13 | - | us/op | 377870.5 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.prefixReplayWindowedTraceCartHistory (eventCount=100, traceWindowSize=10) | 1 | thrpt | 23936.43 | - | ops/s | 377888.6 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.serializeTracedCartHistoryOnly (eventCount=1, traceWindowSize=10) | 1 | avgt | 688.91 | - | ns/op | 1456.1 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.serializeTracedCartHistoryOnly (eventCount=1, traceWindowSize=10) | 1 | thrpt | 1457547.55 | - | ops/s | 1456.1 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.serializeTracedCartHistoryOnly (eventCount=10, traceWindowSize=10) | 1 | avgt | 8.39 | - | us/op | 9504.2 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.serializeTracedCartHistoryOnly (eventCount=10, traceWindowSize=10) | 1 | thrpt | 124685.75 | - | ops/s | 9506.5 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.serializeTracedCartHistoryOnly (eventCount=100, traceWindowSize=10) | 1 | avgt | 354.65 | - | us/op | 1069055.4 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.serializeTracedCartHistoryOnly (eventCount=100, traceWindowSize=10) | 1 | thrpt | 2816.42 | - | ops/s | 1069056.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.sourceCartHistoryOnly (eventCount=1, traceWindowSize=10) | 1 | avgt | 77.73 | - | ns/op | 744.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.sourceCartHistoryOnly (eventCount=1, traceWindowSize=10) | 1 | thrpt | 13117973.52 | - | ops/s | 712.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.sourceCartHistoryOnly (eventCount=10, traceWindowSize=10) | 1 | avgt | 353.14 | - | ns/op | 2256.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.sourceCartHistoryOnly (eventCount=10, traceWindowSize=10) | 1 | thrpt | 2844071.98 | - | ops/s | 2256.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.sourceCartHistoryOnly (eventCount=100, traceWindowSize=10) | 1 | avgt | 4.11 | - | us/op | 52989.6 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.sourceCartHistoryOnly (eventCount=100, traceWindowSize=10) | 1 | thrpt | 227917.44 | - | ops/s | 55389.9 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.stateEventCreationOnly (eventCount=1, traceWindowSize=10) | 1 | avgt | 5.95 | - | ns/op | 88.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.stateEventCreationOnly (eventCount=1, traceWindowSize=10) | 1 | thrpt | 167965149.36 | - | ops/s | 88.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.stateEventCreationOnly (eventCount=10, traceWindowSize=10) | 1 | avgt | 29.44 | - | ns/op | 480.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.stateEventCreationOnly (eventCount=10, traceWindowSize=10) | 1 | thrpt | 34469017.73 | - | ops/s | 480.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.stateEventCreationOnly (eventCount=100, traceWindowSize=10) | 1 | avgt | 262.55 | - | ns/op | 4440.4 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.stateEventCreationOnly (eventCount=100, traceWindowSize=10) | 1 | thrpt | 3654779.52 | - | ops/s | 4440.4 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.suffixTraceWindowOnly (eventCount=1, traceWindowSize=10) | 1 | avgt | 202.74 | - | ns/op | 1832.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.suffixTraceWindowOnly (eventCount=1, traceWindowSize=10) | 1 | thrpt | 4742989.41 | - | ops/s | 1832.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.suffixTraceWindowOnly (eventCount=10, traceWindowSize=10) | 1 | avgt | 3.24 | - | us/op | 26288.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.suffixTraceWindowOnly (eventCount=10, traceWindowSize=10) | 1 | thrpt | 312508.98 | - | ops/s | 26288.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.suffixTraceWindowOnly (eventCount=100, traceWindowSize=10) | 1 | avgt | 3.68 | - | us/op | 27173.2 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.suffixTraceWindowOnly (eventCount=100, traceWindowSize=10) | 1 | thrpt | 294831.47 | - | ops/s | 27172.7 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=1, traceWindowSize=10) | 1 | avgt | 955.51 | - | ns/op | 3272.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=1, traceWindowSize=10) | 1 | thrpt | 1006685.19 | - | ops/s | 3272.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 1 | avgt | 12.23 | - | us/op | 36480.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=10, traceWindowSize=10) | 1 | thrpt | 80444.48 | - | ops/s | 36480.3 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1 | avgt | 576.83 | - | us/op | 2949130.6 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceAndSerializeCartHistory (eventCount=100, traceWindowSize=10) | 1 | thrpt | 1752.89 | - | ops/s | 2951520.5 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceCartHistory (eventCount=1, traceWindowSize=10) | 1 | avgt | 195.14 | - | ns/op | 1784.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceCartHistory (eventCount=1, traceWindowSize=10) | 1 | thrpt | 5062188.04 | - | ops/s | 1784.0 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceCartHistory (eventCount=10, traceWindowSize=10) | 1 | avgt | 3.07 | - | us/op | 25936.1 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceCartHistory (eventCount=10, traceWindowSize=10) | 1 | thrpt | 322536.13 | - | ops/s | 25936.1 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceCartHistory (eventCount=100, traceWindowSize=10) | 1 | avgt | 229.21 | - | us/op | 1795286.5 B/op |
| WebFlux Adapter | AggregateTracingBenchmark.traceCartHistory (eventCount=100, traceWindowSize=10) | 1 | thrpt | 4849.17 | - | ops/s | 1797655.5 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.buildAddCartItemRequest | 1 | avgt | 9.12 | - | us/op | 76896.1 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.buildAddCartItemRequest | 1 | thrpt | 113970.07 | - | ops/s | 77016.1 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.commandResultJsonResponse | 1 | avgt | 822.95 | - | ns/op | 4264.0 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.commandResultJsonResponse | 1 | thrpt | 1191770.32 | - | ops/s | 4240.0 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.extractCommandMessage | 1 | avgt | 246.20 | - | ns/op | 1368.0 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.extractCommandMessage | 1 | thrpt | 4038714.25 | - | ops/s | 1368.0 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.postAddCartItemWaitSent | 1 | avgt | 17.75 | - | us/op | 88548.5 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.postAddCartItemWaitSent | 1 | thrpt | 56952.20 | - | ops/s | 88644.5 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.sendWaitSentCoreFromExtractedMessage | 1 | avgt | 943.87 | - | ns/op | 4020.2 B/op |
| WebFlux Adapter | CommandHandlerFunctionBenchmark.sendWaitSentCoreFromExtractedMessage | 1 | thrpt | 1047100.31 | - | ops/s | 4052.2 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseEventMapping | 1 | avgt | 4.03 | - | us/op | 11528.0 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseEventMapping | 1 | thrpt | 249938.03 | - | ops/s | 11528.0 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseResponse | 1 | avgt | 18.31 | - | us/op | 120464.0 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseResponse | 1 | thrpt | 54432.74 | - | ops/s | 120448.0 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseServerResponseOnly | 1 | avgt | 459.17 | - | ns/op | 3080.0 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseServerResponseOnly | 1 | thrpt | 2180527.15 | - | ops/s | 3080.0 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseWriteToExchange | 1 | avgt | 11.87 | - | us/op | 100288.0 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.commandResultSseWriteToExchange | 1 | thrpt | 81503.81 | - | ops/s | 100328.0 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.fluxJsonStreamingArrayResponse | 1 | avgt | 12.05 | - | us/op | 77760.0 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.fluxJsonStreamingArrayResponse | 1 | thrpt | 82514.49 | - | ops/s | 77816.0 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.monoCommandResultResponse | 1 | avgt | 937.41 | - | ns/op | 4896.0 B/op |
| WebFlux Adapter | WebFluxResponseBenchmark.monoCommandResultResponse | 1 | thrpt | 1068926.62 | - | ops/s | 4896.0 B/op |


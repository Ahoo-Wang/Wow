# wow-webflux Benchmark Design

## Context

`wow-benchmarks` is the central JMH module for Wow. Existing suites cover smoke,
framework command-write E2E, infrastructure E2E, and core component benchmarks.
`wow-webflux` currently has normal unit tests but no benchmark coverage.

Previous source-level analysis identified `wow-webflux` as a thin adapter layer.
The likely cost centers are request parsing, response conversion, SSE wrapping,
and aggregate tracing replay. Those are hypotheses until measured by benchmark
evidence.

## Goals

- Add benchmark coverage for `wow-webflux` without moving JMH into the published
  `wow-webflux` module.
- Keep WebFlux benchmark results separate from the primary Framework E2E
  conclusion source.
- Measure both the command HTTP adapter path and focused adapter hot spots.
- Preserve the existing quick/full JMH profile model, report layout, and
  allocation-focused interpretation through `gc.alloc.rate.norm`.
- Keep the first implementation small enough to compile and smoke-test quickly.

## Non-Goals

- Do not start a real Netty server inside JMH.
- Do not use `WebTestClient` as the first benchmark harness, because it would add
  Spring test harness and HTTP-stack noise before the adapter costs are visible.
- Do not make WebFlux results part of `benchmarkCompare` or the checked-in
  Framework E2E baseline.
- Do not optimize production `wow-webflux` code in the same change.
- Do not add new third-party dependencies.

## Architecture

Add an independent WebFlux benchmark suite inside `:wow-benchmarks`.

The suite depends on `:wow-webflux` from `wow-benchmarks/build.gradle.kts`.
Benchmark classes live under:

```text
wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/webflux/
```

The suite is registered in `wow-benchmarks/gradle/benchmarking.gradle.kts` as a
diagnostic suite:

- `id = "webflux"`
- `displayName = "WebFlux Adapter"`
- `requiredForGroupedReport = false`
- `performanceConclusionSource = false`

This keeps the current Framework E2E suite as the only formal framework
performance conclusion source.

## Benchmark Coverage

### Command Handler Adapter

Add `CommandHandlerFunctionBenchmark`.

This benchmark measures the non-SSE command HTTP adapter path:

- `MockServerRequest` construction
- command body extraction
- `CommandMessageExtractor`
- `CommandGateway.sendAndWait`
- `toCommandResponse`

Use `WAIT_STAGE=SENT` so the benchmark focuses on WebFlux adapter overhead and
does not duplicate full aggregate dispatch cost already covered by Framework E2E.

The benchmark should record handler failures and throw during
`@TearDown(Level.Iteration)` if any operation fails, matching the existing E2E
benchmark pattern.

### Response Conversion

Add `WebFluxResponseBenchmark`.

This benchmark measures:

- `Mono<CommandResult>.toServerResponse`
- non-SSE `Flux<T>.toServerResponse`, including `collectList()`
- SSE wrapping through `ServerSentEvent<String>`

The main signal is allocation per operation, because these paths primarily add
serialization and wrapper-object cost.

### Aggregate Tracing

Add `AggregateTracingBenchmark`.

This benchmark calls `AggregateTracingHandlerFunction.trace()` directly with
parameterized event counts:

```text
1, 10, 100
```

The benchmark isolates replay and copying cost from HTTP and event-store noise.
It is expected to make the cost of repeated prefix replay and `deepCopy()` visible
as stream length grows.

## Gradle Tasks

Add aggregate benchmark tasks following the existing naming style:

```bash
./gradlew :wow-benchmarks:benchmarkQuickWebFlux
./gradlew :wow-benchmarks:benchmarkFullWebFlux
```

These tasks use the existing quick and full profiles.

Quick profile keeps the existing default thread set from `benchmarkQuickThreads`,
currently `1,4`. Full profile keeps the existing `benchmarkThreads` defaults,
currently `1,2,4,8`.

## Smoke Coverage

Add `WebFluxSmokeBenchmark` to the smoke suite with one benchmark method:
`monoCommandResultResponse`.

The method should cover the cheapest stable WebFlux adapter path: a single
`Mono<CommandResult>.toServerResponse` conversion.

The smoke target is health, not performance evidence.

## Reporting

Add the WebFlux suite to grouped reports:

- `generateQuickBenchmarkReport`
- `generateGroupedBenchmarkReport`

Because the suite is optional, missing WebFlux result files should render as
unavailable instead of failing grouped report generation.

Do not add a separate `generateWebFluxBenchmarkReport` task in the first
implementation. Grouped report visibility is enough until the benchmark rows
prove stable.

Update `wow-benchmarks/README.md` with:

- WebFlux Adapter layer description
- quick and full WebFlux commands
- explanation that WebFlux results are adapter diagnostics, not Framework E2E
  conclusion data

## Verification

Implementation should be verified with the narrowest useful commands:

```bash
./gradlew :wow-benchmarks:compileJmhKotlin
./gradlew :wow-benchmarks:benchmarkSmoke
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:generateQuickBenchmarkReport
```

`benchmarkQuickWebFlux -PbenchmarkQuickThreads=1` is the first execution target
because it confirms the new suite works without forcing the slower default
two-thread quick run.

## Risks

- `MockServerRequest` does not include all real HTTP server costs. This is
  intentional for the first suite, which measures adapter behavior rather than
  network capacity.
- WebFlux response benchmarks can overemphasize serialization cost. Interpret
  them with allocation and E2E context, not as standalone service capacity.
- Aggregate tracing benchmark results are diagnostic for long history replay.
  They should not be generalized to normal command handling.
- Adding `:wow-webflux` to `:wow-benchmarks` can expose runtime classpath
  metadata or service-file issues in the JMH jar. Reuse the existing JMH
  packaging merge tasks if conflicts appear.

## Acceptance Criteria

- `:wow-benchmarks` compiles JMH sources with the new WebFlux dependency.
- `benchmarkSmoke` includes `WebFluxSmokeBenchmark.monoCommandResultResponse`.
- `benchmarkQuickWebFlux -PbenchmarkQuickThreads=1` produces JMH JSON and human
  result files under `wow-benchmarks/results/jmh/quick/webflux/`.
- grouped report generation includes WebFlux rows after the suite runs and
  renders WebFlux as unavailable when the suite has not run.
- README documents the new suite and its interpretation policy.

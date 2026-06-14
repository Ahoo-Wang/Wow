# WebFlux Aggregate Tracing Range and Streaming Experiment Design

## Goal

Identify the next production-safe optimization for WebFlux aggregate tracing
after the JSON `ObjectNode` snapshot change.

This step should answer whether the remaining cost is best solved by limiting
the number of traced historical states, reducing intermediate response
materialization, or accepting that full-history full-state tracing is
intrinsically allocation-heavy.

## Evidence

The latest quick WebFlux benchmark breakdown for `eventCount=100` showed:

- `traceCartHistory`: about `1,797,672 B/op`, `219.03 us/op`,
  `4994.36 ops/s`
- `jsonSnapshotCartStateOnly`: about `1,738,272 B/op`, `205.98 us/op`,
  `5143.95 ops/s`
- `serializeTracedCartHistoryOnly`: about `1,069,075 B/op`, `369.24 us/op`,
  `2745.58 ops/s`
- `traceAndSerializeCartHistory`: about `2,949,147 B/op`, `589.39 us/op`,
  `1740.88 ops/s`
- `sourceCartHistoryOnly`: about `52,990 B/op`
- `stateEventCreationOnly` and `jsonSnapshotStateEventCreationOnly`: about
  `4,440 B/op`

That means the current bottleneck is not event replay or `StateEvent` wrapper
construction. It is per-history-state JSON materialization for aggregate
tracing, with response serialization as the second cost layer.

## Current Code Constraints

`AggregateTracingHandlerFunction.handle(...)` currently loads every event for
the aggregate:

```kotlin
eventStore.load(aggregateId = aggregateId)
    .collectList()
    .map {
        aggregateMetadata.state.trace(stateAggregateFactory, it)
    }
```

The `trace(...)` helper replays each event and emits one full JSON state per
historical event:

```kotlin
eventStream.toStateEvent(
    state = stateAggregate.state.toJsonNode<ObjectNode>(),
    ...
)
```

`EventStore.load(...)` already supports inclusive version ranges:

```kotlin
fun load(
    aggregateId: AggregateId,
    headVersion: Int = DEFAULT_HEAD_VERSION,
    tailVersion: Int = DEFAULT_TAIL_VERSION
): Flux<DomainEventStream>
```

The current public tracing route has no range parameters and returns an array
of `StateEvent`. Changing that route without a compatibility decision would be
an API behavior change.

## Selected Approach

Add benchmark-only experiments before changing production code.

The experiment should extend `AggregateTracingBenchmark` with rows that model
the most plausible production designs while preserving the current route
behavior as a baseline.

### Experiment 1: Version-Range Trace

Measure trace cost when only a suffix/range of the history is emitted.

For the benchmark, keep the full prepared `eventStreams` list and add a
`traceWindowSize` parameter, for example `10`. Build a `tailEventStreams` view
from the last `traceWindowSize` events and replay only that window.

Purpose:

- Estimate the benefit if a future tracing endpoint supports `headVersion` and
  `tailVersion`, or a simple `limit`.
- Show how much cost drops when fewer full states are emitted.

Limitation:

- Replaying only a suffix is not equivalent to reconstructing historical state
  unless the window starts from an initial state or a snapshot. This is a
  benchmark model for output-size pressure, not a production implementation
  recipe.

### Experiment 2: Prefix-Replay With Windowed Output

Measure a semantically closer range trace: replay the full history up to the
tail version, but only materialize JSON states for events in the selected
output range.

Purpose:

- Estimate the benefit of range output while preserving correct state at the
  selected versions.
- Separate cheap replay from expensive per-output-state JSON materialization.

This is the most important experiment because it maps to a production-safe
implementation using the existing `EventStore.load(aggregateId, headVersion,
tailVersion)` only after the state base problem is addressed. Without a state
base, the production handler may still need to load from version `1` and skip
materialization before `headVersion`.

### Experiment 3: Direct Serialization Candidate

Measure direct JSON string generation while tracing, without retaining the
intermediate `List<StateEvent<ObjectNode>>` as the final response object.

Purpose:

- Estimate whether avoiding the intermediate response list helps enough after
  JSON state materialization.
- Keep serialization work benchmarked separately from route and Netty overhead.

Limitation:

- A real streaming HTTP implementation would need careful WebFlux response
  handling and error semantics. The benchmark should not be treated as a
  production-equivalent implementation.

## Rejected For This Step

### Add Public Range Parameters Immediately

The storage API already supports version ranges, but the current tracing route
does not expose them. Adding query or path parameters also requires OpenAPI
updates, route compatibility review, and tests. Do this only after benchmark
evidence shows range output is the right lever.

### Replace The Response Serializer Immediately

Response serialization is a real second-layer cost, but the dominant trace cost
is still per-history-state JSON materialization. A serializer change should not
be the first production patch unless direct serialization experiments show a
clear win without semantics risk.

### Change The Response Shape

Returning deltas, events only, or a compact state projection would likely reduce
payload size, but it would change the endpoint contract. That belongs in a
separate API design.

## Implementation Shape

Modify only benchmark code in this step:

- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/webflux/AggregateTracingBenchmark.kt`

Do not modify:

- `wow-webflux/src/main`
- `wow-openapi/src/main`
- generated benchmark reports under `wow-benchmarks/results`

The benchmark should add small private helpers inside
`AggregateTracingBenchmark` if needed, but should not introduce shared
production abstractions.

## Verification

Run the narrow validation path:

```bash
./gradlew :wow-benchmarks:compileJmhKotlin
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:benchmarkQuickE2E -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:generateQuickBenchmarkReport -PbenchmarkQuickThreads=1
```

Extract `AggregateTracingBenchmark` rows from the generated quick grouped
report before restoring generated output:

```bash
rg -n "AggregateTracingBenchmark\\." \
  wow-benchmarks/results/reports/quick-grouped.md \
  wow-benchmarks/results/reports/quick-framework-e2e.md
```

Restore generated benchmark report files before committing.

## Decision Rules

Use `gc.alloc.rate.norm` as the primary signal.

- If prefix-replay with windowed output is close to the selected window size,
  the next production design should add compatible range or limit semantics.
- If direct serialization materially reduces `traceAndSerializeCartHistory`
  while full trace allocation remains high, consider a WebFlux streaming
  response design after range semantics are decided.
- If range/window output does not reduce allocation roughly with emitted state
  count, stop and profile `toJsonNode<ObjectNode>()` itself.
- If the benchmark shows the only meaningful win comes from emitting fewer
  full states, the production fix is an API/semantics design, not a local
  micro-optimization.

## Acceptance Criteria

- The benchmark suite includes range/window and direct serialization candidate
  rows for aggregate tracing.
- Existing aggregate tracing benchmark rows remain available.
- No production code changes in this experiment step.
- `:wow-benchmarks:compileJmhKotlin` passes.
- Quick WebFlux benchmark and grouped quick report can be regenerated.
- Generated benchmark report files are restored before commit.
- The final report identifies whether the next production patch should target
  range semantics, direct streaming serialization, or deeper profiling of JSON
  materialization.

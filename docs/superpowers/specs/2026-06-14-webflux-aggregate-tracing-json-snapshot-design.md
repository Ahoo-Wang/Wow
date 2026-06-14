# WebFlux Aggregate Tracing JSON Snapshot Design

## Goal

Reduce the remaining allocation in
`AggregateTracingBenchmark.traceCartHistory(eventCount=100)` by replacing
typed aggregate state deep copies in the WebFlux aggregate tracing path with a
frozen JSON state snapshot.

The public JSON response shape must remain compatible with the current trace
endpoint. This change should only alter the internal representation used while
building traced `StateEvent` results.

## Evidence

The first aggregate tracing production fix changed the algorithm from repeated
prefix replay to one-pass replay. The follow-up breakdown benchmark then showed
that the remaining hot path is no longer event sourcing or `StateEvent`
construction. It is state snapshotting:

- `traceCartHistory(eventCount=100)`: about `2,843,792 B/op`,
  `995.81 us/op`, and `1072.91 ops/s`
- `copyCartStateOnly(eventCount=100)`: about `2,784,381 B/op`,
  `918.76 us/op`, and `1122.88 ops/s`
- `sourceCartHistoryOnly(eventCount=100)`: about `52,987 B/op` and
  `4.08 us/op`
- `stateEventCreationOnly(eventCount=100)`: about `4,440 B/op` and
  `266.85 ns/op`

The current WebFlux tracing implementation still freezes each historical state
with a Jackson-backed typed copy:

```kotlin
eventStream.toStateEvent(
    state = stateAggregate.state.deepCopy(aggregateType),
    firstOperator = stateAggregate.firstOperator,
    firstEventTime = stateAggregate.firstEventTime,
    tags = stateAggregate.tags,
    deleted = stateAggregate.deleted,
)
```

That preserves correctness, but it materializes a full typed object graph for
every historical state. For aggregates whose state grows over time, the endpoint
still pays one full state copy per output event.

## Selected Approach

Use JSON snapshots for WebFlux aggregate tracing output.

For each event stream:

1. Source the event stream into the single replay aggregate.
2. Convert the current aggregate state to an `ObjectNode`.
3. Build the traced `StateEvent` from the original event stream and the frozen
   `ObjectNode`.

`StateEventJsonSerializer` already writes `value.state` through Jackson, and an
`ObjectNode` writes as the JSON object itself. The HTTP response can therefore
keep the same `state` JSON shape while avoiding typed state object graph
allocation.

This is intentionally scoped to aggregate tracing response construction. It
does not change aggregate sourcing, event stream storage, command handling, or
the core `StateEvent` contract.

## Rejected Approaches

### Reuse the Mutable State Instance

Removing `deepCopy(...)` and passing `stateAggregate.state` directly would be
fast, but incorrect. Every traced `StateEvent` would point at the same mutable
state object, so earlier history entries could show the final state after later
events are sourced.

### Add Pagination or Limits First

Pagination, limits, or cursor-style trace APIs may be useful later because
returning every historical full state has an unavoidable output-size cost. They
also change endpoint semantics and client behavior. This optimization should
first preserve the existing trace contract and only change snapshot
materialization.

### Change Global `StateEvent` Serialization

A serializer-level rewrite could make JSON snapshot events cheaper in more
places, but it would affect a broader surface than the measured WebFlux
hotspot. The first fix should stay local and measurable.

## Implementation Shape

The production change should live in the existing aggregate tracing path:

- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunction.kt`

The implementation can add a small private helper near `trace(...)` if that
keeps the handler readable, for example a helper that converts a sourced state
aggregate into an `ObjectNode` snapshot.

Expected behavior:

- Empty event streams still return an empty list.
- Event streams are still sourced in order with a single state aggregate.
- Each output item still corresponds to the state immediately after the
  matching event stream.
- The serialized `state` JSON remains equivalent to the current typed-state
  response.
- The in-memory traced state value may become `ObjectNode` instead of the
  aggregate's concrete state class in this WebFlux path.

## Tests

Update or add focused tests under:

- `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/state/`

Required coverage:

1. Historical trace entries still capture the correct state progression for a
   cart aggregate.
2. Earlier trace entries remain isolated after later events are sourced.
3. The serialized JSON response for traced state keeps the expected `state`
   object fields.
4. The single-aggregate replay behavior from the previous optimization remains
   covered.

Tests should assert response semantics rather than requiring every traced state
to be the concrete aggregate state class, because the optimized representation
is allowed to be `ObjectNode`.

## Benchmark Verification

After implementation, run the narrow validation ladder:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.state.*AggregateTracing*"
./gradlew :wow-benchmarks:compileJmhKotlin
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:benchmarkQuickE2E -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:generateQuickBenchmarkReport -PbenchmarkQuickThreads=1
```

Use `gc.alloc.rate.norm` as the primary success signal. Compare the refreshed
`traceCartHistory(eventCount=100)` row against the current baseline of about
`2,843,792 B/op`.

## Decision Rules

- If allocation drops materially and response semantics are preserved, keep the
  JSON snapshot implementation.
- If allocation barely changes, do not keep a cosmetic production change. Use
  the measured result to decide whether the next fix should be endpoint
  pagination, output streaming, or serializer-level profiling.
- If response JSON changes unexpectedly, treat that as a correctness failure
  and revise before benchmarking.

## Acceptance Criteria

- Aggregate tracing tests pass with historical state progression and JSON
  response compatibility covered.
- `:wow-benchmarks:compileJmhKotlin` passes.
- Quick WebFlux and grouped quick reports can be regenerated.
- The final report states the before/after allocation and throughput for
  `AggregateTracingBenchmark.traceCartHistory(eventCount=100)`.
- No generated benchmark result files are committed.

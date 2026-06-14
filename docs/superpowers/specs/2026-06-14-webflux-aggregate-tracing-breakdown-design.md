# WebFlux Aggregate Tracing Breakdown Benchmark Design

## Goal

Add benchmark-only evidence for the remaining `AggregateTracingBenchmark`
allocation after the incremental replay optimization. The current
`traceCartHistory` row is still the WebFlux Adapter row with the largest
allocation at `eventCount=100`, but it now mixes several costs:

- event stream sourcing into the cart state aggregate
- per-output `state.deepCopy(aggregateType)` snapshotting
- `StateEvent` construction via `DomainEventStream.toStateEvent(...)`
- list materialization for the traced history result

This change should split those costs before any second production optimization
is proposed.

## Evidence

The first production fix changed aggregate tracing from repeated prefix replay
to one-pass replay with one state snapshot per output event stream. The quick
WebFlux run after that fix showed a large improvement, but the remaining
`AggregateTracingBenchmark.traceCartHistory(eventCount=100)` row still allocates
about `2.84 MB/op` and runs at about `1k ops/s`.

That remaining cost is plausible because the current production path still
copies aggregate state once per historical event stream:

```kotlin
eventStream.toStateEvent(
    state = stateAggregate.state.deepCopy(aggregateType),
    firstOperator = stateAggregate.firstOperator,
    firstEventTime = stateAggregate.firstEventTime,
    tags = stateAggregate.tags,
    deleted = stateAggregate.deleted,
)
```

However, the benchmark does not yet prove whether `deepCopy`, aggregate
sourcing, or `StateEvent` construction dominates. The next step is therefore a
measurement split, not a runtime change.

## Scope

Touch only benchmark code under:

```text
wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/webflux/
```

Expected file:

- `AggregateTracingBenchmark.kt`

Do not change production code under `wow-webflux/src/main`.
Do not change aggregate tracing tests in this benchmark-only step.
Do not commit generated benchmark JSON or report markdown unless explicitly
requested.

## Benchmark Rows

Extend `AggregateTracingBenchmark` while keeping the existing
`traceCartHistory` total row:

1. `sourceCartHistoryOnly`
   - Create a fresh cart state aggregate.
   - Source every prepared event stream into that aggregate.
   - Consume the final aggregate or state.
   - Purpose: isolate event sourcing/replay cost without state snapshot copies
     or `StateEvent` output creation.

2. `copyCartStateOnly`
   - Prepare a cart state by sourcing the same event streams during setup.
   - During the benchmark, copy that prepared state with the same
     Jackson-backed `deepCopy(aggregateType)` helper used by production.
   - Repeat enough copies to match `eventCount`, so the row is comparable with
     the total trace row that emits one state snapshot per event stream.
   - Purpose: isolate the per-history-entry state snapshot cost.

3. `stateEventCreationOnly`
   - Prepare a representative cart state snapshot and aggregate metadata during
     setup.
   - During the benchmark, call `DomainEventStream.toStateEvent(...)` for the
     prepared event streams using the prepared state value and aggregate
     metadata.
   - Do not call `deepCopy(...)` inside this row.
   - Purpose: isolate `StateEvent` object creation and event stream wrapping
     from state copy cost.

The existing `traceCartHistory` remains the adapter-level total row for
aggregate tracing and must keep using
`BenchmarkAggregates.cartMetadata.state.trace(...)`.

## Implementation Notes

- Keep the existing `@Param("1", "10", "100")` so every focused row can be
  compared across the same event counts.
- Use immutable prepared lists or freshly created publishers/aggregates where a
  benchmark invocation mutates state.
- Do not reuse a mutable state aggregate across invocations for sourcing rows.
- The copy row may copy the same prepared final state `eventCount` times; that
  intentionally approximates the production row's one-copy-per-output shape
  while removing sourcing and `StateEvent` construction.
- The `stateEventCreationOnly` row should avoid creating new state copies; it
  exists to decide whether the wrapper object cost is material compared with
  sourcing and copying.
- Do not add response serialization or WebFlux `ServerResponse.writeTo(...)`
  rows in this step. Aggregate tracing response rendering can be measured later
  if sourcing/copy/event construction do not explain the total.

## Verification

Run the narrow benchmark validation path:

```bash
./gradlew :wow-benchmarks:compileJmhKotlin
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:benchmarkQuickE2E -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:generateQuickBenchmarkReport -PbenchmarkQuickThreads=1
```

`benchmarkQuickE2E` is included because grouped quick report generation expects
Framework E2E input.

## Decision Rules

Use `gc.alloc.rate.norm` as the main decision signal:

- If `copyCartStateOnly` explains most of `traceCartHistory(eventCount=100)`,
  design a separate production optimization around snapshotting strategy.
- If `sourceCartHistoryOnly` dominates, inspect aggregate sourcing/replay and
  event payload shape before changing trace output.
- If `stateEventCreationOnly` dominates, inspect `StateEvent` construction and
  event stream reference/copy behavior.
- If no focused row explains the total, stop and use an allocation profiler
  before proposing production changes.

## Acceptance Criteria

- WebFlux quick benchmark output includes the three new aggregate tracing
  breakdown rows.
- `traceCartHistory` remains available as the total aggregate tracing row.
- `:wow-benchmarks:compileJmhKotlin` passes.
- The refreshed WebFlux quick report can explain whether the remaining
  aggregate tracing cost is primarily sourcing, state copy, or state event
  construction.
- No production code is changed in this benchmark-only step.

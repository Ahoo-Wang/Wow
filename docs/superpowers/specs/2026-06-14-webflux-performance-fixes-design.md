# WebFlux Performance Fixes Design

## Goal

Fix the performance issues exposed by the WebFlux Adapter benchmarks one at a
time, starting with the highest-confidence production hotspot and then using the
refreshed benchmark evidence to decide whether the command-send path needs a
production change or only clearer benchmark interpretation.

## Evidence

The new WebFlux Adapter quick benchmark suite exposed two different classes of
cost:

- `AggregateTracingBenchmark.traceCartHistory(eventCount=100)` is the clearest
  production hotspot. The current implementation rebuilds each historical state
  by creating a new state aggregate, replaying every prefix from the start, and
  deep-copying each prefix event stream. For 100 event streams this performs
  roughly 5050 replay/copy units before response serialization is considered.
- `CommandHandlerFunctionBenchmark.postAddCartItemWaitSent` shows meaningful
  adapter allocation, but the focused rows show most of the measured cost comes
  from mock request construction and adapter fixture shape. The core gateway
  sent wait row is small by comparison, even though it includes a required
  prepared message copy to avoid reusing a read-only message header.

These costs should not be fixed with the same kind of change. Aggregate tracing
has a concrete algorithmic issue in production code. Command sending currently
has a measurement interpretation issue and needs more evidence before changing a
runtime path.

## Approach

### 1. Optimize Aggregate Tracing First

Change `AggregateTracingHandlerFunction.trace(...)` from repeated prefix replay
to incremental replay:

1. Create one state aggregate for the aggregate id.
2. Iterate through event streams once in order.
3. Source the next event stream into the aggregate.
4. Emit a `StateEvent` for that event stream using an independent state snapshot.

The output must remain a list of state events where each item represents the
aggregate state immediately after the corresponding event stream. The important
semantic guard is that earlier `StateEvent.state` values must not point at the
same mutable state object that later sourcing mutates.

The existing `StateEvent.Companion.toStateEvent(state: S, ...)` overload can be
used with a copied state value to preserve snapshot semantics while avoiding
full prefix replay and repeated event stream deep copies. This makes the replay
work linear in the number of event streams, with one state copy per output
state.

### 2. Validate With Tests Before Code Changes

Add a focused test around `StateAggregateMetadata.trace(...)` using the cart
aggregate and a counting `StateAggregateFactory`:

- Build multiple cart event streams for the same aggregate.
- Call `CartState.trace(...)`.
- Assert the result has one state event per input event stream.
- Assert each state event captures the item count at that point in history.
- Assert the first state event does not change after later events are sourced.
- Assert the state aggregate factory is called exactly once for a non-empty
  event stream list.

The factory-count assertion is the RED test: the current implementation creates
one state aggregate per historical prefix, so it calls the factory once per
event stream. The snapshot assertions are semantic guards: they must pass after
the implementation changes and ensure the new single-aggregate replay does not
return multiple state events backed by the same mutable state instance.

### 3. Re-Benchmark Aggregate Tracing

After the test passes, rerun:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.state.*AggregateTracing*"
./gradlew :wow-benchmarks:compileJmhKotlin
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickThreads=1
```

Compare `AggregateTracingBenchmark.traceCartHistory` before and after on the
same machine. The expected direction is a substantial reduction in
`gc.alloc.rate.norm` and average time for `eventCount=100`. The exact percentage
is evidence-driven and will be reported from the benchmark output rather than
guessed in the design.

### 4. Reassess Command Send Path Second

Only after the aggregate tracing change is measured should the command-send
path be revisited. The follow-up decision is:

- If refreshed data still shows production-path work in WebFlux command
  handling, design a narrow second fix with its own failing test and benchmark.
- If the remaining cost is dominated by `MockServerRequest` construction or
  benchmark-only fixture cost, leave production code unchanged and document the
  interpretation in benchmark docs or report text.

No command-send production code change should be made in the aggregate tracing
fix. This avoids mixing a real algorithmic improvement with speculative adapter
micro-optimization.

## Files

The aggregate tracing fix is expected to touch:

- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunction.kt`
- `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunctionTest.kt`
  or a new adjacent test file if the focused trace test is clearer there

The benchmark suite should not require structural changes for the first fix.
Generated files under `wow-benchmarks/results/` must remain uncommitted.

## Risks

- State snapshot correctness is the main risk. If state objects are mutable and
  earlier results share the same instance, trace output would incorrectly show
  the final state for every historical event. The failing test must guard this.
- Copying only state, rather than copying every event stream prefix, changes the
  allocation profile but should preserve the response contract: each state event
  contains the original event stream plus the state after that event.
- Some aggregate state types may have custom serialization or mutable nested
  collections. The implementation should use the existing Jackson-backed
  `deepCopy` helper for state snapshots to match project serialization behavior.

## Acceptance Criteria

- A focused WebFlux aggregate tracing test fails before the implementation and
  passes after it.
- Existing aggregate tracing handler tests still pass.
- `:wow-webflux:test` for aggregate tracing tests passes.
- `:wow-benchmarks:compileJmhKotlin` passes.
- `benchmarkQuickWebFlux -PbenchmarkQuickThreads=1` passes.
- The after-state report clearly states whether aggregate tracing improved and
  whether command-send production code needs a second fix.

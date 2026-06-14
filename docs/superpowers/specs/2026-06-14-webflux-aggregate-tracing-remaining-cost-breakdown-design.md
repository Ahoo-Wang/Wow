# WebFlux Aggregate Tracing Remaining Cost Breakdown Design

## Goal

Add benchmark-only evidence for the remaining
`AggregateTracingBenchmark.traceCartHistory(eventCount=100)` cost after the JSON
snapshot optimization.

This step should identify whether the next optimization target is JSON snapshot
materialization, `StateEvent` wrapping around JSON snapshots, response JSON
serialization, or the unavoidable output size of returning every historical
full state.

## Evidence

The latest production optimization replaced typed aggregate state
`deepCopy(aggregateType)` with `toJsonNode<ObjectNode>()` snapshots for the
WebFlux aggregate tracing path. Quick local WebFlux benchmark evidence showed a
clear improvement:

- allocation: about `2,843,792 B/op` to about `1,797,519 B/op`
- average time: about `995.81 us/op` to about `221.34 us/op`
- throughput: about `1072.91 ops/s` to about `5000.51 ops/s`

The remaining focused rows for `eventCount=100` now show:

- `traceCartHistory`: about `1,797,519 B/op`
- `sourceCartHistoryOnly`: about `52,987 B/op`
- `stateEventCreationOnly`: about `4,440 B/op`
- old typed `copyCartStateOnly`: about `2,784,394 B/op`

That proves aggregate sourcing and simple `StateEvent` construction are not the
remaining bottleneck. It does not yet prove whether the new remaining cost comes
from `toJsonNode<ObjectNode>()`, JSON state tree retention, final response
serialization, or simply the size of the historical state payload.

## Selected Approach

Add benchmark-only breakdown rows to `AggregateTracingBenchmark`.

The benchmark should continue to use the same cart event history and
`@Param("1", "10", "100")` event counts as the existing aggregate tracing rows.
No production code should change in this step.

### New Rows

1. `jsonSnapshotCartStateOnly`
   - Prepare historical `CartState` instances in setup.
   - During the benchmark, convert each prepared state to
     `ObjectNode` with `toJsonNode<ObjectNode>()`.
   - Purpose: isolate JSON tree snapshot materialization cost from replay and
     `StateEvent` wrapping.

2. `jsonSnapshotStateEventCreationOnly`
   - Prepare one `ObjectNode` state snapshot per historical event stream.
   - During the benchmark, wrap those prepared snapshots with
     `DomainEventStream.toStateEvent(...)`.
   - Purpose: measure the cost of creating traced state events when JSON
     snapshots already exist.

3. `serializeTracedCartHistoryOnly`
   - Prepare a traced cart history once in setup using the production
     `trace(...)` helper.
   - During the benchmark, serialize the prepared traced history with
     `toJsonString()`.
   - Purpose: measure response-body JSON serialization cost after trace output
     exists.

4. `traceAndSerializeCartHistory`
   - Run production `trace(...)` and then serialize the result with
     `toJsonString()`.
   - Purpose: approximate the non-SSE HTTP response body cost for aggregate
     tracing before Spring `ServerResponse` wrapper overhead.

### Existing Rows To Keep

Keep these rows unchanged so the report continues to show the full context:

- `traceCartHistory`
- `sourceCartHistoryOnly`
- `copyCartStateOnly`
- `stateEventCreationOnly`

## Rejected Approaches

### Change Trace API Semantics Now

Pagination, limits, and streaming trace output may be the eventual fix if the
dominant cost is returning every historical full state. They change endpoint
semantics and should not be introduced before the remaining cost is measured.

### Add Serializer-Level Production Optimizations Now

Custom serializers or token-buffer based response generation may reduce
allocation, but they touch a broader serialization surface. They should wait
until benchmark rows show serialization is the dominant removable cost.

### Remove Historical State Snapshots

Returning only events or deltas would avoid the output-size cost, but it would
change the aggregate tracing contract. That is outside this benchmark-only
step.

## Implementation Shape

Modify only:

- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/webflux/AggregateTracingBenchmark.kt`

The benchmark setup should prepare:

- the event stream list
- historical typed cart states, still useful for typed copy and JSON snapshot
  rows
- historical `ObjectNode` snapshots
- traced history from the production `trace(...)` helper
- metadata fields used by `toStateEvent(...)`

The implementation should avoid mutating shared prepared lists during benchmark
invocations.

## Verification

Run the narrow benchmark validation path:

```bash
./gradlew :wow-benchmarks:compileJmhKotlin
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:benchmarkQuickE2E -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:generateQuickBenchmarkReport -PbenchmarkQuickThreads=1
```

`benchmarkQuickE2E` remains included because grouped quick report generation
expects the quick E2E inputs.

After report generation, extract all aggregate tracing rows:

```bash
rg -n "AggregateTracingBenchmark\\." \
  wow-benchmarks/results/reports/quick-grouped.md \
  wow-benchmarks/results/reports/quick-framework-e2e.md
```

Generated benchmark outputs must be restored before committing.

## Decision Rules

Use `gc.alloc.rate.norm` as the primary signal:

- If `jsonSnapshotCartStateOnly` explains most of `traceCartHistory`, the next
  production design should target state snapshot materialization.
- If `serializeTracedCartHistoryOnly` or `traceAndSerializeCartHistory`
  dominates, the next production design should target response serialization or
  streaming output.
- If the new rows add up to approximately the full trace response cost and the
  cost scales with history size, the next design should evaluate trace
  pagination or range limits.
- If no row explains the remaining allocation, stop and use allocation
  profiling before proposing another production change.

## Acceptance Criteria

- WebFlux quick benchmark output includes the four new aggregate tracing
  breakdown rows.
- Existing aggregate tracing benchmark rows remain available.
- `:wow-benchmarks:compileJmhKotlin` passes.
- Quick WebFlux and grouped quick reports can be regenerated.
- The final report identifies the most likely next optimization target without
  changing production code.
- No generated benchmark result files are committed.

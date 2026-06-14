# WebFlux SSE Response Breakdown Benchmark Design

## Goal

Add benchmark-only evidence for the WebFlux SSE command response path before
changing production code. The current `commandResultSseResponse` benchmark is a
useful total signal, but it mixes command result to SSE event mapping,
`ServerResponse` construction, SSE HTTP message writing, and
`MockServerWebExchange` allocation.

This change should split those costs into focused rows so the next production
decision is evidence-driven.

## Evidence

After the aggregate tracing fix, the remaining WebFlux quick benchmark row that
still stands out is:

```text
WebFluxResponseBenchmark.commandResultSseResponse
```

That row allocates about `121 KB/op` on the refreshed quick WebFlux run. The
number is too mixed to justify a production optimization by itself because the
benchmark currently does all of the following in one method:

- creates a `Flux<CommandResult>`
- maps each command result to `ServerSentEvent<String>`
- serializes each command result with `toJsonString()`
- creates a `ServerResponse` with `TEXT_EVENT_STREAM`
- writes the response into a fresh `MockServerWebExchange`

The next step is therefore another measurement split, not a runtime change.

## Scope

Touch only benchmark code under:

```text
wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/webflux/
```

Expected files:

- `WebFluxResponseBenchmark.kt`
- `WebFluxBenchmarkSupport.kt` if helper fixtures make the benchmark methods
  clearer

Do not change production code under `wow-webflux/src/main`.
Do not commit generated benchmark JSON or report markdown unless explicitly
requested.

## Benchmark Rows

Extend `WebFluxResponseBenchmark` with these focused rows while keeping the
existing `commandResultSseResponse` total row:

1. `commandResultSseEventMapping`
   - Start from prepared `CommandResult` values.
   - Map them to `ServerSentEvent<String>` with the same id, event, and JSON
     data shape used by `Flux<CommandResult>.toCommandResponse(...)`.
   - Consume the resulting events so the benchmark includes mapping and
     serialization work, not only publisher assembly.

2. `commandResultSseServerResponseOnly`
   - Start from a prepared SSE event stream.
   - Build the `ServerResponse` through the same `toEventStreamResponse(...)`
     path used by command SSE responses.
   - Do not call `writeTo(...)`.
   - Purpose: isolate response object construction from HTTP message writing.

3. `commandResultSseWriteToExchange`
   - Start from the same command-result SSE response path as the total row.
   - Write it to a `MockServerWebExchange` with the existing SSE response
     context.
   - Purpose: make the Spring writer/mock exchange cost visible as its own
     measurement.

The existing `commandResultSseResponse` remains the adapter-level total row.
If the new `writeTo` row and the old total row are almost identical, the main
cost is likely in Spring response writing or the benchmark mock exchange. If the
event mapping row is large, then command result JSON/SSE conversion deserves a
separate production design.

## Implementation Notes

- Keep fixture count aligned with the existing response benchmark payload count
  so row comparisons stay intuitive.
- Prefer small benchmark methods over clever shared abstractions.
- Avoid reusing a consumed `Flux`; construct a fresh publisher per invocation
  or use immutable prepared lists as the source.
- Keep method names stable and descriptive because grouped reports use method
  names as the analysis surface.
- Do not broaden the quick WebFlux suite or add dependencies.

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

- If `commandResultSseEventMapping` dominates, design a narrow follow-up around
  command SSE serialization/event mapping.
- If `commandResultSseWriteToExchange` dominates, do not optimize Wow
  production code based on this row alone; document that the measured cost is
  mostly Spring writer/mock exchange behavior.
- If no focused row clearly explains the total, stop and inspect the generated
  JMH raw output before proposing production changes.

## Acceptance Criteria

- WebFlux quick benchmark output includes the three new SSE breakdown rows.
- `commandResultSseResponse` remains available as the total response-path row.
- `:wow-benchmarks:compileJmhKotlin` passes.
- The refreshed WebFlux quick report can explain whether the SSE response path
  has a Wow production hotspot or only benchmark/Spring writer overhead.
- No production code is changed in this benchmark-only step.

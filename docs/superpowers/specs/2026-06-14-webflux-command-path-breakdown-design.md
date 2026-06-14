# WebFlux Command Path Breakdown Benchmark Design

## Context

The first WebFlux benchmark suite added `CommandHandlerFunctionBenchmark.postAddCartItemWaitSent`.
That benchmark is useful as an adapter-level end-to-end signal, but it blends
several costs:

- `MockServerRequest` and body publisher construction
- command body extraction
- `DefaultCommandMessageExtractor` and `DefaultCommandBuilderExtractor`
- `CommandGateway.sendAndWait` with `WAIT_STAGE=SENT`
- non-SSE command response conversion and JSON serialization

The quick single-thread result for `postAddCartItemWaitSent` was about
`56k ops/s`, `17.44 us/op`, and `88.9 KB/op`. The core command gateway
`sendAndWaitSent` path is much lighter, so the next benchmark increment should
split the WebFlux adapter path into explainable segments.

## Goals

- Explain where the WebFlux command-send adapter cost comes from.
- Keep the existing `postAddCartItemWaitSent` benchmark as the total adapter
  path signal.
- Add focused benchmark methods that isolate request construction, command
  message extraction, gateway sent wait, and response conversion.
- Keep the benchmarks inside `:wow-benchmarks`; do not change production
  `wow-webflux` behavior.
- Preserve the existing WebFlux suite, report layout, and `gc.alloc.rate.norm`
  interpretation.

## Non-Goals

- Do not optimize production code in this change.
- Do not add a real Netty server or `WebTestClient` harness.
- Do not add new third-party dependencies.
- Do not make WebFlux results part of the Framework E2E baseline or
  `benchmarkCompare`.

## Benchmark Design

Extend the WebFlux benchmark package under:

```text
wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/webflux/
```

Add focused benchmark methods for the command path:

1. `buildAddCartItemRequest`
   - Measures `MockServerRequest` plus `AddCartItem` body publisher construction.
   - Purpose: separate benchmark harness/request construction cost from handler
     logic.

2. `extractCommandMessage`
   - Reuses a prepared request and command body.
   - Measures `DefaultCommandMessageExtractor` plus
     `DefaultCommandBuilderExtractor`.
   - Purpose: isolate metadata, path/header/principal, command builder, and
     command message creation cost.

3. `sendWaitSentCoreFromExtractedMessage`
   - Reuses a prepared `CommandMessage`.
   - Measures `CommandGateway.sendAndWait` with a SENT wait plan through the
     same gateway scenario used by the WebFlux benchmark.
   - Purpose: compare adapter-local gateway cost against the standalone
     Framework E2E sent path.

4. `commandResultJsonResponse`
   - Reuses a prepared `CommandResult`.
   - Measures non-SSE `Flux<CommandResult>.toCommandResponse` /
     `Mono<*>.toServerResponse` response construction.
   - Purpose: isolate command response and JSON serialization cost.

5. Keep `postAddCartItemWaitSent`
   - Continues to measure the full WebFlux adapter path.
   - Purpose: preserve the existing headline row and compare it with the focused
     rows.

The focused rows may live in `CommandHandlerFunctionBenchmark` if that keeps
shared setup simple. If the file becomes hard to scan, split helper state into
`WebFluxBenchmarkSupport` and keep the benchmark methods short.

## Reporting

No new Gradle suite or report task is needed. These methods remain part of the
existing `WebFlux Adapter` suite through the existing include class:

```text
me.ahoo.wow.benchmark.webflux.CommandHandlerFunctionBenchmark
```

Grouped reports should automatically display the new methods after
`benchmarkQuickWebFlux` runs.

## Verification

Use the narrowest useful commands:

```bash
./gradlew :wow-benchmarks:compileJmhKotlin
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:benchmarkQuickE2E -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:generateQuickBenchmarkReport -PbenchmarkQuickThreads=1
```

`benchmarkQuickE2E` is included only because grouped quick report generation
requires Framework E2E results.

## Acceptance Criteria

- WebFlux quick benchmark output includes the five command-path rows listed in
  this spec.
- The focused rows make it possible to compare request construction, extraction,
  gateway sent wait, response conversion, and total adapter cost.
- Existing WebFlux benchmark task names and report integration continue to work.
- No generated JMH JSON or generated report markdown is committed unless the
  user explicitly requests refreshed report evidence.

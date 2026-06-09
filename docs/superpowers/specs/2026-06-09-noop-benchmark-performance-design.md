# Noop Benchmark Performance Design

## Context

The current checkout is at `origin/main` with recent command hotpath work already merged. The active noop performance evidence is in `wow-benchmarks`:

- `CommandWriteE2EBenchmark` covers Framework E2E command write scenarios: `ceiling`, `noop-store`, and `in-memory-new-aggregate`.
- The checked-in quick Framework E2E report shows `noop-store` as the highest allocation framework scenario.
- Component benchmark output is currently absent from `quick-grouped.md`, so existing reports identify the E2E symptom but do not isolate the next fixed-cost source.

The current `noop-store` quick E2E rows show:

- `sendAndWaitProcessed`, 1 thread: about `86.5k ops/s`, about `6.9 KB/op`.
- `sendAndWaitSent`, 1 thread: about `756.6k ops/s`, about `6.2 KB/op`.

This means the next optimization should not start by changing persistence. The sent path already allocates heavily before aggregate processing, and the processed path adds aggregate/filter costs on top of that baseline.

## Goal

Improve noop command-write benchmark performance by reducing fixed allocation and latency in the command wait/sent path, while preserving public API behavior and reactive semantics.

Success is measured by:

- A focused component benchmark that explains the fixed overhead in the wait/sent path.
- Lower `gc.alloc.rate.norm` and equal or higher throughput in the focused benchmark after optimization.
- No regression in `CommandWriteE2EBenchmark` `noop-store` rows for `sendAndWaitSent` and `sendAndWaitProcessed`.
- Passing `wow-core` tests and checks.

## Non-Goals

This design does not change:

- Gradle module structure.
- Redis, Mongo, R2DBC, Kafka, WebFlux, OpenAPI, or generated dashboard clients.
- Event store, snapshot repository, or infrastructure persistence behavior.
- The public `WaitStrategy`, `CommandGateway`, or `CommandBus` API contracts.
- Command result wire format or error result semantics.

## Recommended Approach

Use a diagnostic-first workflow:

1. Add a narrow `wow-benchmarks` component benchmark for the wait/sent path.
2. Use it to isolate whether fixed allocation comes mainly from `waitingLast()`, wait signal creation/completion, header propagation, or in-memory bus send/receive.
3. Optimize only the proven hotspot in `wow-core`.
4. Validate with unit tests, smoke benchmark, focused before/after JMH, and Framework E2E noop rows.

This is preferred over direct code changes because the current reports rank the E2E symptom but do not contain component rows for cause analysis.

## Architecture

The work is bounded to two modules:

- `wow-benchmarks` gains a focused component benchmark for diagnosis.
- `wow-core` receives a small wait/sent hotpath optimization only after benchmark evidence identifies the target.

No storage modules or transport modules are changed. The optimization remains local to command wait handling and command send path fixed overhead.

## Components

### Focused Component Benchmark

Add a component benchmark that can independently measure:

- `WaitingForStage.waitingLast()` on a single-stage wait.
- Wait signal creation and completion.
- Header propagation for wait metadata.
- Optional in-memory command bus send/receive overhead if the first rows show the wait strategy alone is not enough to explain E2E allocation.

The benchmark is diagnostic evidence, not a standalone framework capacity claim.

### Wait Path Optimization

If `waitingLast()` is confirmed as a hotspot, optimize the single-stage path away from:

- collecting all signals into a list,
- sorting by signal time,
- merging result maps,
- copying the last signal unconditionally.

The optimization must preserve the current behavior for:

- multiple signals,
- previous-stage failure signals,
- result map merging,
- chain waits,
- final cleanup through `onFinally`.

The public `WaitStrategy.waitingLast()` method remains unchanged.

### E2E Regression Anchor

Use `CommandWriteE2EBenchmark` `noop-store` as the final benchmark anchor:

- `sendAndWaitSent` should reflect wait/sent fixed-cost reduction.
- `sendAndWaitProcessed` should improve only if the removed fixed cost was shared by the processed path.

The focused component benchmark explains the cause; the E2E benchmark confirms user-visible framework path impact.

## Data Flow

The relevant noop command path is:

`CommandGateway.sendAndWait*` -> `WaitingForStage` propagates wait metadata into the command header -> `InMemoryCommandBus` sends the command -> the dispatcher or processed filter emits a `WaitSignal` -> `waitingLast()` turns the final signal into a `CommandResult`.

The design reduces fixed allocation inside this flow. It does not change command processing order, event stream publishing, state event publishing, idempotency checks, or validation behavior.

## Error Handling

Existing error semantics must be preserved:

- Send failures still map through `CommandResultException`.
- Wait signal error fields remain intact.
- Previous-stage failures still terminate waits correctly.
- `WaitStrategy.onFinally` still unregisters wait strategies.
- Result maps from multiple signals are still merged in the same effective order for paths that need merging.

Any fast path must be guarded so it only applies when it is semantically equivalent to the existing general path.

## Testing

Add or update `wow-core` tests for:

- single-signal `waitingLast()` success,
- multiple-signal result merge behavior,
- previous-stage failure handling,
- completion and unregister behavior,
- unchanged command wait result conversion.

Add or update `wow-benchmarks` smoke coverage if the focused benchmark should remain part of the PR safety gate.

## Verification

Use this validation ladder:

1. Run a focused before benchmark for the new diagnostic component rows.
2. Implement the smallest proven optimization.
3. Run `./gradlew :wow-core:test`.
4. Run `./gradlew :wow-core:check`.
5. Run `./gradlew :wow-benchmarks:benchmarkSmoke --stacktrace`.
6. Run the same focused JMH after benchmark and compare throughput plus `gc.alloc.rate.norm`.
7. If the focused rows improve, run `./gradlew :wow-benchmarks:benchmarkQuickE2E :wow-benchmarks:generateBenchmarkReport` to validate the `noop-store` E2E rows.

Only benchmark output from commands actually run in this worktree should be used as final evidence.

## Risks

- `waitingLast()` supports more than the simple sent/processed path; a fast path can break multi-signal waits if applied too broadly.
- Header mutation must respect read-only boundaries after messages are sent.
- E2E benchmark variance can hide small wins, so component rows should explain the expected mechanism.
- Benchmarks are local-machine directional feedback unless full E2E runs are performed.

## Acceptance Criteria

- The focused benchmark exists and can isolate wait/sent fixed overhead.
- The optimized code preserves existing public API behavior.
- All relevant `wow-core` tests pass.
- `:wow-core:check` passes.
- `:wow-benchmarks:benchmarkSmoke --stacktrace` passes.
- Focused before/after JMH shows reduced allocation for the chosen hotspot.
- `noop-store` E2E rows do not regress and preferably improve in allocation and throughput.

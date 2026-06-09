# Lazy Command Function Resolution Design

## Context

The quick Framework E2E benchmark isolates Wow command-pipeline overhead with in-memory or noop infrastructure. The current quick report shows `CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store)` around `13.20 us/op` and `6967-7309 B/op` on the checked-in local run.

In the current command path, each command creates or loads a `SimpleCommandAggregate`. The constructor eagerly builds:

- the full command function registry via `CommandAggregateMetadata.toCommandFunctionRegistry(this)`;
- the full error function registry via `CommandAggregateMetadata.toErrorFunctionRegistry(this)`.

For the benchmarked `Cart` aggregate, the noop E2E path sends `AddCartItem`, while the aggregate metadata also contains other command handlers and default internal command fallbacks. Eager binding therefore pays fixed per-aggregate cost for functions that this command does not use.

## Goals

- Avoid binding unused command and error functions when constructing `SimpleCommandAggregate`.
- Preserve existing command handling, error handling, after-command, and default internal command semantics.
- Reduce `gc.alloc.rate.norm` in the quick noop E2E benchmark.
- Keep throughput neutral or improved in the quick noop E2E benchmark.
- Keep the change inside `wow-core` command metadata and aggregate runtime boundaries.

## Non-Goals

- Do not change public API behavior or command wire contracts.
- Do not redesign the broader command pipeline, dispatcher, retry, event sourcing, or wait-notification flow.
- Do not add new dependencies.
- Do not make formal performance claims from quick JMH results alone; quick results are directional.

## Design Decision

Use "C boundaries, B scope":

- Treat `CommandAggregateMetadata` as the immutable command handling plan.
- Add a small per-aggregate lazy resolver that belongs to the `SimpleCommandAggregate` runtime instance.
- Resolve and bind only the command function required by the current command type.
- Resolve and bind error functions only on the error path.
- Cache bound functions per aggregate instance, but create caches lazily so the resolver itself does not reintroduce eager fixed allocation.

This keeps the architecture clean without turning the change into a broad framework rewrite.

## Architecture

`CommandAggregateMetadata` remains the owner of immutable metadata:

- explicit command function metadata;
- explicit error function metadata;
- after-command function metadata;
- default internal command availability flags for recover, delete, and apply-resource-tags.

`SimpleCommandAggregate` remains the runtime coordinator:

- validates version, initialization, ownership, space, command state, and deletion state;
- invokes the resolved command function;
- sources and stores events;
- delegates error handler lookup to the resolver only when an error occurs.

The new resolver is an internal implementation detail:

- bound to one `SimpleCommandAggregate` instance and its `commandRoot`;
- starts without materialized command or error cache maps;
- lazily allocates a command cache only after the first command function is bound;
- lazily allocates an error cache only when an error handler is actually bound;
- does not expose new public API.

## Command Resolution Flow

When `SimpleCommandAggregate.process()` receives a command:

1. Keep all existing pre-checks unchanged.
2. Ask the resolver for `commandFunction(commandType)`.
3. The resolver first checks its instance-level command cache if already allocated.
4. On a miss, it resolves the command type from metadata:
   - prefer `commandFunctionRegistry[commandType]` when explicitly registered;
   - otherwise provide the existing default internal fallback when the aggregate has not registered a compatible recover, delete, or apply-resource-tags command;
   - otherwise report an undefined command, preserving the current failure behavior.
5. Bind only the selected command function to the current aggregate instance.
6. Bind after-command functions only for the selected command type, preserving include, exclude, and order rules.
7. Cache the bound command function for this aggregate instance.

## Error Resolution Flow

The success path does not resolve or bind error functions.

When command processing fails:

1. Preserve the current `exchange.setError(error)` behavior.
2. Ask the resolver for `errorFunction(commandType)`.
3. If no explicit error function metadata exists for the command type, propagate the original error.
4. If metadata exists, bind that single error function to the current aggregate instance, cache it lazily, and invoke it.
5. Preserve the current behavior that returns the original exchange error when the error function does not clear or replace it.

Default internal commands do not gain implicit error handlers.

## Performance Impact

The expected benefit is fixed-overhead removal:

- fewer unused `MessageFunction` bindings per aggregate instance;
- fewer unused `CommandFunction` and internal command wrapper allocations;
- no successful-path error function binding;
- no complete registry map construction on aggregate creation.

For quick noop E2E, the expected primary signal is lower `gc.alloc.rate.norm`. Throughput should be neutral or improved, but quick JMH throughput can vary more than allocation. The implementation must avoid eager resolver cache allocation; otherwise the improvement can be partially offset.

This optimization is intentionally narrow. It should improve the command hot path but is not expected to remove all overhead visible in the quick noop E2E benchmark.

## Testing

Behavior tests should cover:

- constructing `SimpleCommandAggregate` does not require materializing a complete command or error registry;
- command functions are resolved by command type and still process normal commands;
- after-command include, exclude, and order behavior remains unchanged;
- default recover, delete, and apply-resource-tags command fallback behavior remains unchanged;
- error functions resolve only on the error path;
- missing error functions still propagate the original error.

Recommended focused commands:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.modeling.metadata.CommandAggregateMetadataTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.modeling.command.SimpleCommandAggregateProcessingTest"
./gradlew :wow-core:check
```

Benchmark validation:

```bash
./gradlew :wow-benchmarks:benchmarkSmoke
./gradlew :wow-benchmarks:benchmarkQuickE2E :wow-benchmarks:generateBenchmarkReport
```

The main benchmark row is:

`CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store)`

Compare threads `1` and `4` for:

- `gc.alloc.rate.norm`;
- `thrpt`;
- `avgt`.

Acceptance:

- all behavior tests pass;
- `:wow-benchmarks:benchmarkSmoke` passes;
- quick noop E2E allocation decreases;
- quick noop E2E throughput does not show a clear regression.

## Risks

- A resolver that eagerly creates caches or binds after-command functions would erode the intended allocation win.
- Default internal command fallback must preserve the existing `registeredRecoverAggregate`, `registeredDeleteAggregate`, and `registeredApplyResourceTags` semantics.
- Error handler behavior is easy to change accidentally because it is only observable on failures.
- Quick benchmark results are directional; use allocation as the primary local signal and avoid overclaiming throughput from one short run.

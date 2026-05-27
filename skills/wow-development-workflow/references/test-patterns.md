# Development Workflow Test Notes

Use `../../wow/references/testing.md` as the authoritative Wow testing reference. This file only explains how tests map to the development workflow.

## Choose The Test Shape

| Workflow Element | Preferred Test | What It Proves |
|------------------|----------------|----------------|
| Aggregate command handler | `AggregateSpec` | command behavior, emitted events, sourced state, errors |
| Aggregate lifecycle | `AggregateSpec` with `fork` or `ref` | delete, recover, terminal-state, branch behavior |
| Stateless saga handler | `SagaSpec` | event-triggered command generation or no-command behavior |
| Saga with dependency | `SagaSpec` plus injected mock | process decision while keeping external dependency narrow |

Before using a DSL method, confirm it exists in the current checkout:

```bash
rg -n "class .* : AggregateSpec|AggregateSpec<|expectEventType|expectState|fork\\(" . -g "*.kt"
rg -n "class .* : SagaSpec|SagaSpec<|expectCommand|expectNoCommand|inject \\{" . -g "*.kt"
```

## Aggregate Scenario Mapping

Convert each aggregate scenario into one expectation block:

- Happy path: `expectNoError()`, event type or body, and resulting state.
- Error path: `expectErrorType(...)` or a specific error assertion.
- Edge state: use `fork(...)` or `ref(...)` to branch from a meaningful prior state.
- Lifecycle: include delete or recover behavior when the aggregate supports lifecycle commands.

## Saga Scenario Mapping

Convert each saga scenario into one orchestration expectation:

- Trigger branch: `expectCommandType(...)` and command body assertions.
- No-command branch: `expectNoCommand()` when the trigger condition is not met.
- Routing branch: assert target aggregate id, owner, tenant, or command metadata when exposed by the DSL.
- Multi-command branch: assert command count and each command body.

## Mocking Guidance

Use real events and state when they are cheap to construct. Use `mockk` for external services or read-model queries injected into command or saga handlers.

Keep mocks narrow and verify behavior through emitted events, generated commands, or state transitions rather than implementation details.

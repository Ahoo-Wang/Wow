# Test Scenario Document Template

Use this before or while writing tests so behavior coverage is explicit.

## Template

```markdown
# <AggregateName> Test Cases

## Aggregate Behavior

| Scenario | Given | When | Expected | Test/Evidence | Status |
|----------|-------|------|----------|---------------|--------|
| <description> | <events or state> | <command> | <event, state, or error> | <test name or command/result> | Pending |

## Saga Orchestration

Include this section only when the feature has a saga.

| Scenario | Given Event | Expected Command | Runtime Concern | Test/Evidence | Status |
|----------|-------------|------------------|-----------------|---------------|--------|
| <description> | <event and relevant metadata> | <command or no command> | <none, duplicate delivery, retry, or stale input> | <test name or command/result> | Pending |
```

If the repository already has test-case documents, mirror the nearest existing example.

## Coverage Requirements

### Aggregate Behavior

| Scenario Type | Description |
|---------------|-------------|
| Happy path | Valid command succeeds with the expected event and state. |
| Error path | Invalid command fails with the expected exception or error. |
| Edge state | Special existing state such as disabled, deleted, stale, empty, or completed. |
| Idempotency | Duplicate or stale command behavior when supported by the domain. |
| Lifecycle | Delete, recover, or terminal-state behavior when the aggregate supports it. |

### Saga Orchestration

| Scenario Type | Description |
|---------------|-------------|
| Trigger condition met | Event satisfies the condition and the correct command is generated. |
| Trigger condition not met | Event does not satisfy the condition and no command is generated. |
| Branch condition | Each branch emits the expected command or no command. |
| Multi-command | One event generates multiple commands when the process requires it. |
| Duplicate delivery | Repeated events do not create unintended business effects; verify at the runtime boundary that enforces idempotency. |
| Stale input | Older or out-of-order input follows an explicit reject, ignore, or reconcile policy. |
| Retry policy | Recoverable, terminal, and exhausted failures are covered at the `EventCompensationFilter` or integration boundary when the saga uses `@Retry`. |

## Status Markers

- `Pending`: scenario designed but not implemented.
- `Done`: test implemented and freshly passing; record the test or command/result in `Test/Evidence`.
- `Fail`: test implemented but failing and needs investigation.

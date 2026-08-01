---
name: wow-debugging
description: Use when Wow commands, events, sourcing, sagas, projections, event processors, command gateways, wait plans, Query DSL, retry policies, starter configuration, PrepareKey, WowRuntime lifecycle, or tests fail, hang, skip handlers, produce unexpected state, or behave inconsistently
---

# Wow Debugging

Find the broken link in the Wow pipeline before fixing code. Guessing at event-sourced systems creates new symptoms because command, event, state, projection, and wait layers are coupled by metadata.

## Iron Law

Keep diagnosis read-only by default. Reproduce, locate the failing stage, and compare with a working example. Modify code only when the user explicitly asks for a fix; then make one minimal, test-backed change.

## Reference Routing

Do not route through `../wow/SKILL.md`; select only the package-shared references needed for the failing scope.

| Failure scope | Load |
|---|---|
| Aggregate, command, event, state, Saga | `../wow/references/modeling.md`, `../wow/references/annotations.md`, `../wow/references/testing.md` as needed |
| Projection or EventProcessor | `../wow/references/annotations.md`, `../wow/references/testing.md` |
| Gateway, wait, idempotency | `../wow/references/command-gateway.md` |
| Query DSL | `../wow/references/dsl.md` |
| Starter, storage, bus, feature configuration | `../wow/references/configuration.md` |
| PrepareKey | `../wow/references/prepare-key.md` |
| WowRuntime ownership, readiness, fatal handling, graceful shutdown | Inspect `wow-core/src/main/kotlin/me/ahoo/wow/runtime/`, `wow-spring/src/main/kotlin/me/ahoo/wow/spring/WowRuntimeLifecycle.kt`, and `documentation/docs/zh/guide/advanced/runtime-lifecycle.md` |
| Handler missing, unregistered, or not selected | Load `references/handler-discovery.md`, then the domain reference above only as needed |

## Phase 1: Reproduce

- Capture the exact command, test, endpoint, event, or configuration that fails.
- Run the narrowest command that reproduces it.
- Read the full error, stack trace, emitted logs, and failing assertion.
- Check recent diffs before proposing a fix.

## Phase 2: Locate the Stage

| Symptom | First Checks |
|---------|--------------|
| Command not handled | Command route, aggregate metadata, `@AggregateRoot`, `@OnCommand`, KSP output, command bus. |
| Aggregate state wrong | Event payload, `@OnSourcing`, missing sourcing handler, snapshot/replay path. |
| Saga not triggered | Event type, `@StatelessSaga`, `@OnEvent`, filter condition, processor metadata, bus subscription. |
| Handler runs but fails or retries incorrectly | Original exception, `@Retry`, retry predicate, `EventCompensationFilter`, retry exhaustion, duplicate delivery. |
| Projection not updated | Processor annotation, event/state-event type, repository call, retry policy. |
| Wait plan hangs | Wait command id, stage, context name, processor/function names, propagated headers. |
| Query returns wrong data | Query DSL condition, deletion guard, tenant/owner filters, projection fields, backend converter. |
| Configuration ignored | `@ConfigurationProperties` prefix, feature capability, conditional annotations, active profile. |
| Runtime never becomes ready or shutdown hangs | `WowRuntime` state, lifecycle owner, admission/drain transition, fatal cause, quiet period, deadline, and Spring lifecycle phase. |
| Test fails unexpectedly | Test fixture state, owner/tenant id, fork/ref checkpoint, expected event order. |

## Phase 3: Gather Evidence

Use source-first searches before edits:

```bash
rg -n "@AggregateRoot|@OnCommand|@OnSourcing|@StatelessSaga|@EventProcessor|@ProjectionProcessor|@OnEvent|@OnStateEvent" . -g "*.kt" -g "*.java"
rg -n "CommandWait|CommandWait.chain|Command-Wait" . -g "*.kt" -g "*.java"
rg -n "@ConfigurationProperties|ConditionalOn.*Enabled|class .*Properties" . -g "*.kt" -g "*.java"
rg -n "AggregateSpec<|SagaSpec<|AggregateVerifier|SagaVerifier|aggregateVerifier|sagaVerifier|expectEventType|expectCommand|expectNoCommand" . -g "*.kt" -g "*.java"
rg -n "WowRuntime|RuntimeComponent|WowRuntimeLifecycle|GracefullyStoppable" . -g "*.kt" -g "*.java"
```

Find a similar working path in the same repository and list meaningful differences. Do not assume a difference is irrelevant until checked. Load `references/handler-discovery.md` only when a handler is missing, unregistered, unmatched, or unselected; do not run its KSP/JAR chain for unrelated configuration, Query DSL, runtime lifecycle, or ordinary test failures.

## Phase 4: Test the Hypothesis

State one hypothesis: "stage X fails because Y." Test it first with the smallest non-mutating diagnostic, focused reproducer, or existing test. If it fails, discard the hypothesis and return to evidence gathering.

When the user has authorized a code fix, prefer adding or tightening:

- `AggregateSpec` for aggregate behavior and sourcing.
- `SagaSpec` for trigger/no-command/multi-command behavior.
- Unit tests for projection or configuration behavior.
- Focused integration tests only when the failure crosses infrastructure boundaries.

## Red Flags

- Changing annotations without checking generated/discovered metadata.
- Fixing a projection when the event was never emitted.
- Fixing wait plan names before checking command ids.
- Adding retries before understanding the original failure.
- Updating tests to match broken state.
- Trying multiple fixes in one patch.

## Completion Evidence

For diagnosis-only work, finish with the exact reproducer, confirmed failing stage, evidence, and remaining unknowns; do not claim a fix. For an authorized fix, also include the new failing test or equivalent pre-fix evidence, the verification command, and its result.

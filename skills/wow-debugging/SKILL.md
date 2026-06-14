---
name: wow-debugging
description: Use when Wow commands, events, sourcing, sagas, projections, wait plans, Query DSL, retry policies, starter configuration, or tests fail, hang, skip handlers, produce unexpected state, or behave inconsistently
---

# Wow Debugging

Find the broken link in the Wow pipeline before fixing code. Guessing at event-sourced systems creates new symptoms because command, event, state, projection, and wait layers are coupled by metadata.

## Iron Law

No fix before root cause. Reproduce, locate the failing stage, compare with a working example, then make one minimal change.

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
| Saga not triggered | Event type, `@StatelessSaga`, `@OnEvent`, filter condition, bus subscription, `@Retry` policy. |
| Projection not updated | Processor annotation, event/state-event type, repository call, retry policy. |
| Wait plan hangs | Wait command id, stage, context name, processor/function names, propagated headers. |
| Query returns wrong data | Query DSL condition, deletion guard, tenant/owner filters, projection fields, backend converter. |
| Configuration ignored | `@ConfigurationProperties` prefix, feature capability, conditional annotations, active profile. |
| Test fails unexpectedly | Test fixture state, owner/tenant id, fork/ref checkpoint, expected event order. |

## Phase 3: Gather Evidence

Use source-first searches before edits:

```bash
rg -n "@AggregateRoot|@OnCommand|@OnSourcing|@StatelessSaga|@ProjectionProcessor" . -g "*.kt"
rg -n "CommandWait|CommandWait.chain|Command-Wait" . -g "*.kt"
rg -n "@ConfigurationProperties|ConditionalOn.*Enabled|class .*Properties" . -g "*.kt"
rg -n "AggregateSpec<|SagaSpec<|expectEventType|expectCommand|expectNoCommand" . -g "*.kt"
```

Find a similar working path in the same repository and list meaningful differences. Do not assume a difference is irrelevant until checked.

## Phase 4: Test the Hypothesis

State one hypothesis: "stage X fails because Y." Test it with the smallest change or diagnostic. If it fails, discard the hypothesis and return to evidence gathering.

For code fixes, prefer adding or tightening:

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

Finish with the exact reproducer and verification command. If the issue is not fully fixed, report the current failing stage and the remaining unknowns.

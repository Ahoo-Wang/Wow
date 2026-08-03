---
name: wow-develop
description: Design, implement, test, refactor, or explain Wow framework behavior and APIs in Kotlin or Java reactive DDD, Event Sourcing, and CQRS applications. Use when the primary outcome is new or changed Wow code, tests, domain modeling, or focused API guidance involving aggregates, sourcing, sagas, projections, event processors, command delivery, Query DSL, starter configuration, storage, runtime lifecycle, or PrepareKey. Do not use when the primary outcome is reviewing an existing diff, diagnosing an observed failure, or migrating an existing Wow v6 application to v8.
---

# Develop Wow Applications

Own the complete development task. Do not route to another Wow Skill.

## Contract

- Treat the current checkout, its tests, generated contracts, and resolved dependencies as authoritative.
- Treat commands as intent, domain events as committed facts, and sourced state as reconstructed memory.
- Keep aggregate invariants inside the aggregate boundary and external side effects outside it.
- Preserve reactive execution, serialization compatibility, module boundaries, and public contracts unless the user authorizes a breaking change.
- Use RED→GREEN→REFACTOR for behavior changes. If a change is not testable at the unit level, name the narrowest replacement evidence before editing.
- Report exact commands, results, changed behavior, and remaining uncertainty.

## Workflow

1. **Frame**: state the requested outcome, writable scope, compatibility boundary, and completion evidence.
2. **Discover**: inspect `settings.gradle.kts`, relevant source, neighboring implementations, tests, configuration, generated contracts, and recent diffs.
3. **Model**: identify the responsible boundary, invariant, message flow, failure behavior, and required compatibility.
4. **Prove**: add or tighten the smallest failing test or equivalent pre-change evidence.
5. **Change**: implement the smallest coherent design; avoid introducing a second source of truth.
6. **Verify**: run the narrowest relevant test/check first, then broaden only when risk requires it.
7. **Report**: distinguish verified behavior from inference and list every unverified external boundary.

## Load one domain reference first

| Primary scope | Load |
|---|---|
| Aggregate, command, event, sourcing, lifecycle, tenant/owner routing | `references/aggregate-sourcing.md` |
| Saga, Projection, EventProcessor, retry, idempotency | `references/saga-processors.md` |
| CommandGateway, wait, delivery ambiguity, HTTP command routes | `references/command-delivery.md` |
| Query DSL, read-model filtering, pagination, projection, sort | `references/query-read-model.md` |
| Spring Boot starter, feature capability, storage or bus routing | `references/starter-storage.md` |
| Runtime ownership, readiness, fatal handling, drain or shutdown | `references/runtime-lifecycle.md` |
| Uniqueness, reservation, rollback, or reprepare with PrepareKey | `references/prepare-key.md` |
| Selecting tests and deciding whether evidence is sufficient | `references/verification-evidence.md` |

Load a second reference only when the task genuinely crosses domains. Read only the relevant sections, then verify every exact symbol and default in the current source.

## Source discovery

Start with `rg --files` and `rg`. Resolve actual module names instead of assuming placeholders.

```bash
rg -n "@AggregateRoot|@OnCommand|@OnSourcing|@StatelessSaga|@ProjectionProcessor|@EventProcessor" . -g '*.kt' -g '*.java'
rg -n "AggregateSpec<|SagaSpec<|aggregateVerifier|sagaVerifier" . -g '*.kt' -g '*.java'
rg -n "@ConfigurationProperties|class .*Properties" . -g '*.kt'
rg -n "WowRuntime|RuntimeComponent|WowRuntimeLifecycle|GracefullyStoppable" . -g '*.kt' -g '*.java'
```

For an annotation, configuration property, DSL method, gateway API, or generated contract:

1. Find its definition.
2. Find every compiler/runtime consumer.
3. Find representative tests.
4. Compare the target module's usage.
5. Update generated outputs only through their source or generator.

## Implementation gates

- Do not mutate aggregate state from command handlers.
- Keep sourcing deterministic and side-effect free.
- Test every material branch, including no-event/no-command and error paths.
- Test projection and processor retry/idempotency at the runtime layer that owns them.
- Do not introduce blocking or manual subscription into reactive runtime paths.
- Inspect current `@ConfigurationProperties` before changing configuration examples.
- Verify event/schema/API compatibility when changing public messages or metadata.
- Use the assertion style already established by the target module; Kotlin Wow tests normally use `me.ahoo.test.asserts.assert` and `.assert()`.

## Optional output assets

- Copy `assets/behavior-scenarios.md` only when the user requests a scenario document.
- Copy `assets/design-report.md` only when the user requests a design report.

Do not load either asset merely to answer or implement a task.

## Completion

Finish with the aligned goal, changed files and behavior, verification commands and results, compatibility or operational risk, and remaining evidence gaps. Never replace an unavailable test with “should pass.”

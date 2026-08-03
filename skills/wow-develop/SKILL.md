---
name: "wow-develop"
description: "Design, implement, test, refactor, or explain Wow framework and domain behavior in Kotlin or Java reactive DDD, Event Sourcing, and CQRS applications. Use when the primary outcome is changed Wow code, tests, modeling, or source-backed API and configuration guidance for aggregates, sourcing, sagas, projections, processors, command delivery, Query DSL, starter or storage setup, runtime lifecycle, or PrepareKey. Also use for first-time adoption and routine same-major service upgrades with no known breaking source or configuration contract and no data migration. Do not use for diff review, observed-failure diagnosis, or breaking migration and data cutover work. Do not activate merely because work occurs in the Wow repository; exclude Gradle, build, CI, release metadata, dependency-only maintenance, dashboards, frontends, documentation translation, and unrelated Kotlin or Java work unless Wow framework or domain semantics are the outcome."
---

# Develop Wow Applications

Own the complete development task. Do not route to another Wow Skill.

## Contract

- Treat the current checkout, its tests, generated contracts, and resolved dependencies as authoritative.
- Treat commands as intent, domain events as committed facts, and sourced state as reconstructed memory.
- Keep aggregate invariants inside the aggregate boundary and external side effects outside it.
- Preserve reactive execution, serialization compatibility, module boundaries, and public contracts unless the user authorizes a breaking change.
- Keep explanation, lookup, design-only, and other read-only requests non-mutating. Enter test-first implementation only when the user requests a code or document change.
- Use RED→GREEN→REFACTOR for behavior changes. If a change is not testable at the unit level, name the narrowest replacement evidence before editing.
- Report exact commands, results, changed behavior, and remaining uncertainty.

## Workflow

1. **Frame**: state the requested outcome, writable scope, compatibility boundary, and completion evidence.
2. **Discover**: inspect `settings.gradle.kts`, relevant source, neighboring implementations, tests, configuration, generated contracts, and recent diffs.
3. **Model**: identify the responsible boundary, invariant, message flow, failure behavior, and required compatibility.
4. **Prove**: for read-only guidance, verify the requested facts against current source and tests. For an authorized behavior change, add or tighten the smallest failing test or name equivalent pre-change evidence.
5. **Change**: only within the authorized writable scope, implement the smallest coherent design; avoid introducing a second source of truth.
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

For read-only work, finish with the aligned question, source-backed answer, verification performed, and remaining evidence gaps. For authorized changes, also report changed files and behavior plus compatibility or operational risk. Never replace an unavailable test with “should pass.”

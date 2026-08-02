---
name: wow
description: |
  Router, focused implementation guide, and reference entry for Wow framework semantics in reactive DDD + Event Sourcing + CQRS services on JVM 17+ with Spring Boot.

  Use for mixed or ambiguous Wow tasks; focused framework lookup; or clearly scoped Kotlin implementation involving @CommandRoute or other Wow annotations, WebFlux/OpenAPI command routes, projections, event processors, command gateways, waits, Query DSL, starter configuration, storage routing, buses, feature switches, PrepareKey, or runtime lifecycle.

  Use wow-development-workflow directly for clearly scoped end-to-end aggregate or saga implementation. Use wow-code-review or wow-debugging directly for review or diagnosis.

  Use wow-v6-to-v8-migration directly for auditing, planning, implementing, or verifying an existing Wow v6 application's upgrade to Wow v8.

  Do not trigger for unrelated Kotlin, Gradle, frontend, or documentation tasks unless Wow framework behavior or APIs are directly relevant.
---

# Wow Framework Skill

Use this to route mixed or ambiguous Wow work, answer focused framework questions, and implement clearly scoped Kotlin framework behavior outside the Aggregate/Saga workflow.

Use direct specialists for initial Aggregate/Saga implementation, review, and diagnosis. During those specialist phases, select package-shared references without routing back here. For each explicitly authorized non-Aggregate/Saga implementation pass in a combined review-and-fix task, enter this skill once, then return to `wow-code-review` for post-fix review.

Always verify exact APIs in the current checkout before editing code.

## Source-First Rule

Before writing or changing Wow code, verify the current implementation with `rg`, `rg --files`, and nearby source files. Examples in this skill and its references are navigation aids, not a substitute for the target repository's APIs.

Useful first searches:

```bash
rg -n "@AggregateRoot|@OnCommand|@OnSourcing|@StatelessSaga|@ProjectionProcessor" . -g "*.kt" -g "*.java"
rg -n "AggregateSpec<|SagaSpec<|aggregateVerifier|sagaVerifier|AggregateVerifier|SagaVerifier" . -g "*.kt" -g "*.java"
rg -n "WowRuntime|RuntimeComponent|WowRuntimeLifecycle|GracefullyStoppable" . -g "*.kt" -g "*.java"
rg -n "@ConfigurationProperties|class .*Properties" wow-spring-boot-starter -g "*.kt"
```

## Task Routing

| User Task | Load |
|-----------|------|
| Implement or change aggregate or saga domain behavior, model, lifecycle, or tests | `../wow-development-workflow/SKILL.md` |
| Look up aggregate modeling or annotation semantics without changing behavior | `references/modeling.md`, then `references/annotations.md` |
| Implement or change `@CommandRoute`, command route metadata, or WebFlux/OpenAPI command routing | `references/annotations.md`, then verify the current definitions and tests across `wow-api`, `wow-openapi`, and `wow-webflux` |
| Implement or change any other Wow annotation or its behavior | `references/annotations.md`, then locate the current annotation definition and every runtime or compiler consumer with `rg` before editing |
| Implement, add, or strengthen AggregateSpec or SagaSpec behavior tests | `../wow-development-workflow/SKILL.md`, then `references/testing.md` |
| Look up isolated testing DSL, verifier, fork/ref, lifecycle-test, or FluentAssert APIs | `references/testing.md` |
| Build saga orchestration or cross-aggregate process behavior | `../wow-development-workflow/SKILL.md`, then `references/annotations.md` and `references/testing.md` |
| Implement or change projection or event processor behavior | `references/annotations.md`, then `references/testing.md` |
| Implement or change command gateway, wait plan, wait chain, idempotency, HTTP wait headers | `references/command-gateway.md` |
| Change runtime ownership, readiness, fatal handling, or graceful shutdown | Verify `wow-core/src/main/kotlin/me/ahoo/wow/runtime/`, `wow-spring/src/main/kotlin/me/ahoo/wow/spring/WowRuntimeLifecycle.kt`, and `documentation/docs/zh/guide/advanced/runtime-lifecycle.md` before editing |
| Implement or change Query DSL, pagination, projection, sort, query service calls | `references/dsl.md` |
| Implement or change Spring Boot starter, storage, buses, feature switches | `references/configuration.md` |
| Implement or change uniqueness or reservation with PrepareKey | `references/prepare-key.md` |
| Audit, plan, implement, or verify an existing application's Wow v6 to v8 upgrade | `../wow-v6-to-v8-migration/SKILL.md` |
| Review Wow code, PR diffs, framework semantics, or test coverage | `../wow-code-review/SKILL.md` |
| Debug failing commands, events, sourcing, sagas, projections, waits, queries, config, or tests | `../wow-debugging/SKILL.md` |

## Core Model

Wow applications normally separate command handling from state mutation:

```text
Command -> Command Aggregate -> Event -> EventStore -> EventBus
                                      -> State Aggregate sourcing
                                      -> Projection / Saga / EventProcessor
```

Prefer the Aggregate Pattern: command aggregate handles commands and returns events; state aggregate mutates only through sourcing handlers. Avoid direct state mutation in command handlers.

## Quality Gates

Before finishing Wow code changes, check:

- Command handlers return domain events and do not mutate state directly.
- Commands and domain events include `@Summary` and `@Description` metadata when they are part of the API/domain contract.
- Important repeated domain fields are modeled with `<FieldName>Capable` interfaces where reuse improves clarity.
- State changes happen through deterministic sourcing handlers.
- Handlers returning polymorphic `Any` or multiple event types declare explicit return metadata when the current API requires it.
- Saga logic has both trigger and no-command tests when conditions branch.
- Aggregate behavior is tested with `AggregateSpec` or `AggregateVerifier`; saga orchestration is tested with `SagaSpec` or `SagaVerifier`.
- Projection and event processor side effects are outside aggregates.
- Kotlin value and collection assertions use `me.ahoo.test.asserts.assert` / `.assert()`; Wow DSL/verifier assertions continue to use their current `expect*` and `verify()` APIs.
- Gradle commands use resolved module names from `settings.gradle.kts`, not hard-coded `api` or `domain` placeholders.
- Verification commands are reported exactly.

## References

| Reference | When to Use |
|-----------|-------------|
| `references/modeling.md` | Aggregate pattern, bounded context, lifecycle, routing, state rebuild |
| `references/annotations.md` | Annotation parameters and handler conventions |
| `references/testing.md` | AggregateSpec, SagaSpec, verifier APIs, fork/ref, FluentAssert |
| `references/command-gateway.md` | Wait plans, idempotency, LocalFirst, command rewriter, HTTP headers |
| `references/dsl.md` | Query DSL operators, pagination, sort, projection, query execution |
| `references/configuration.md` | Spring Boot starter configuration and feature switches |
| `references/prepare-key.md` | PrepareKey uniqueness/reservation workflows |

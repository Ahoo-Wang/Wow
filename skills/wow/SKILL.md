---
name: wow
description: |
  Wow framework assistant for building reactive DDD + Event Sourcing + CQRS microservices in Kotlin/Java on JVM 17+ with Spring Boot.

  Use this skill when a task involves Wow framework semantics:
  - DDD aggregates, command/event/state modeling, bounded contexts, tenant/owner routing
  - CQRS, Event Sourcing, event stores, snapshots, projections, read models
  - Saga orchestration, event processors, retry policy, PrepareKey
  - Command gateway, wait plans, command bus, WebFlux command endpoints
  - WowRuntime, RuntimeComponent, readiness, fatal shutdown, graceful shutdown, Spring lifecycle ownership
  - Wow tests: AggregateSpec, SagaSpec, AggregateVerifier, SagaVerifier
  - Wow annotations such as @AggregateRoot, @OnCommand, @OnSourcing, @OnEvent, @StatelessSaga, @ProjectionProcessor, @EventProcessor, @AfterCommand, @OnError, @Retry, @BoundedContext, @CreateAggregate, @CommandRoute

  Do not trigger for unrelated Kotlin, Gradle, frontend, or documentation tasks unless Wow framework behavior or APIs are directly relevant.
---

# Wow Framework Skill

Use this as the router for Wow framework work. For end-to-end aggregate or saga development, route to `../wow-development-workflow/SKILL.md`. For focused lookup, load the smallest reference file that matches the task, then verify exact APIs in the current checkout before editing code.

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
| Develop, complete, restructure, or enhance aggregate or saga behavior end-to-end | `../wow-development-workflow/SKILL.md` |
| Model or change aggregate, command, event, state, bounded context, lifecycle | `references/modeling.md`, then `references/annotations.md` |
| Add or update annotations | `references/annotations.md` |
| Write AggregateSpec, SagaSpec, verifier, lifecycle tests, FluentAssert assertions | `references/testing.md` |
| Build saga orchestration or cross-aggregate process behavior | `../wow-development-workflow/SKILL.md`, then `references/annotations.md` and `references/testing.md` |
| Build projection or event processor behavior | `references/annotations.md`, then `references/testing.md` |
| Use command gateway, wait plan, wait chain, idempotency, HTTP wait headers | `references/command-gateway.md` |
| Change runtime ownership, readiness, fatal handling, or graceful shutdown | Verify `wow-core/src/main/kotlin/me/ahoo/wow/runtime/`, `wow-spring/src/main/kotlin/me/ahoo/wow/spring/WowRuntimeLifecycle.kt`, and `documentation/docs/zh/guide/advanced/runtime-lifecycle.md` before editing |
| Write Query DSL, pagination, projection, sort, query service calls | `references/dsl.md` |
| Configure Spring Boot starter, storage, buses, feature switches | `references/configuration.md` |
| Implement uniqueness or reservation with PrepareKey | `references/prepare-key.md` |
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
- Aggregate behavior is tested with `AggregateSpec`; saga orchestration is tested with `SagaSpec`.
- Projection and event processor side effects are outside aggregates.
- Tests use `me.ahoo.test.asserts.assert` / `.assert()`.
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

## Skill Maintenance

When this skill set changes, validate every project-local skill with the standard `skill-creator` validator, validate `evals/evals.json` with `jq`, and run forward tests that exercise the affected guidance:

```bash
for skill_file in skills/*/SKILL.md; do
  python3 "${CODEX_HOME:-$HOME/.codex}/skills/.system/skill-creator/scripts/quick_validate.py" "$(dirname "$skill_file")"
done
jq empty skills/wow/evals/evals.json
```

---
name: wow-aggregate-enhance
description: >
  PROACTIVELY use this skill when the user asks to enhance, complete, or polish a Wow framework aggregate.
  Trigger on phrases like: "complete the X aggregate", "add comments to aggregate", "write aggregate unit tests",
  "create aggregate design report", "supplement aggregate test coverage", "完善聚合", "补充聚合注释",
  "编写聚合测试", or any request to add comments, tests, or design docs to a DDD aggregate.
  Also trigger when a Wow aggregate has incomplete KDoc comments, missing test scenarios, or no design report.
  Guides an interactive step-by-step process: comments → test scenarios → unit tests → design report.
---

# Wow Aggregate Enhancement

Systematically enhance a Wow framework DDD aggregate: add KDoc comments, write comprehensive test scenarios,
implement unit tests, and produce an aggregate design report.

## Core Principles

- **Interactive execution**: Pause after each phase for user confirmation before proceeding.
- **No business logic changes**: Only add comments, tests, and documentation. Never modify command/event/state behavior.
- **Follow project conventions**: Match the project's existing code style, comment language, and test patterns.

## Workflow

Execute these 4 phases in order. Commit after each phase and ask the user to confirm.

```
Comments → Test Scenarios → Unit Tests → Design Report
```

---

## Phase 1: KDoc Comments

### Goal

Add KDoc comments to all API-layer and Domain-layer files for the aggregate.

### Steps

1. **Find aggregate files**: Use Grep or Glob to locate all `.kt` files for the aggregate (API commands/events/interfaces + Domain aggregate/state/saga).

2. **Read and analyze**: Read each file to understand command semantics, business rules, and state transition logic.

3. **Add comments by layer**:
   - **API interfaces**: Document ID generation algorithm, field meanings, computed property logic.
   - **API commands/events**: Document command responsibilities, use cases, validation rules.
   - **Domain aggregate**: Document idempotency protection, business constraints, `@OnCommand` parameters/return values/exceptions.
   - **Domain state**: Document event sourcing mechanism, `@OnSourcing` trigger sources and state changes.

4. **Verify compilation**: `./gradlew api:compileKotlin domain:compileKotlin`

5. **Commit in batches**: Interfaces → Commands/Events → Domain layer.

### Comment Standards

See `references/comment-standards.md` for detailed KDoc conventions and examples.

---

## Phase 2: Test Scenarios

### Goal

Write a test scenario document covering all command handlers and sagas for the aggregate.

### Steps

1. **Catalog handlers**: List all `@OnCommand` methods on the aggregate and all `@OnEvent`/`@OnStateEvent` methods on sagas.

2. **Design scenarios**: Use the table format from `references/test-case-template.md`.

3. **Write document**: Save to `document/test-cases/<AggregateName>TestCases.md`.

4. **Commit**.

### Coverage Requirements

- Each command handler: at minimum happy path + error path + edge case.
- Saga: trigger condition met + trigger condition not met (if applicable).
- See `references/test-case-template.md` for the complete template.

---

## Phase 3: Unit Tests

### Goal

Implement unit tests based on Phase 2 scenarios using Wow framework test utilities.

### Steps

1. **Determine test type**:
   - Aggregate commands → `AggregateSpec` (Given-When-Expect pattern)
   - Saga event handling → `SagaSpec` + mockk

2. **Write aggregate tests**: Reference AggregateSpec patterns in `references/test-patterns.md`.

3. **Write saga tests**: Reference SagaSpec patterns in `references/test-patterns.md`.

4. **Run and verify**: `./gradlew domain:test --tests "com.xxx.domain.xxx.XxxSpec"`

5. **Commit**.

---

## Phase 4: Design Report

### Goal

Produce an aggregate design report documenting architectural decisions, command/event/state design, and interface hierarchy.

### Steps

1. **Use the template**: Reference `references/design-report-template.md`.

2. **Write file**: Save to `docs/aggregate-design/<AggregateName>-aggregate-design.md`.

3. **Include**:
   - Overview (aggregate responsibility, ID generation rules)
   - Command design (each command's responsibility, fields, validation)
   - Event design (each event's payload and trigger conditions)
   - State model (field table, validity rules, event sourcing mapping)
   - Saga orchestration (if any)
   - Interface hierarchy (inheritance diagram)
   - Test scenarios (reference Phase 2 document)
   - Appendix (file index)

4. **Commit**.

---

## After Completion

Run `./gradlew check` to verify all checks pass.

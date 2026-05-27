# Aggregate And Saga Design Report Template

## File Location

Default: `docs/aggregate-design/<AggregateName>-design.md`

If the repository already has a design document convention, use the existing location and naming style.

## Template

```markdown
# <AggregateName> Design Report

## Overview

<One sentence describing the aggregate responsibility and core domain concept>.

## Design Philosophy Applied

- Aggregate invariant: <what the aggregate owns>
- Event fact: <why the emitted event is a durable fact>
- API metadata: <how Summary and Description describe commands and events>
- Shared field vocabulary: <which fields are modeled as Capable interfaces>
- State sourcing: <how state is rebuilt>
- Saga coordination: <only if cross-aggregate orchestration exists>

## Aggregate ID Rule

```text
id = <components>
```

<Explain uniqueness, tenant or owner routing, and compatibility constraints>.

## Command Design

| Command | Summary | Description | Event | Important Rules |
|---------|---------|-------------|-------|-----------------|
| `CommandA` | <summary> | <description> | `EventA` | <invariant or validation> |

## Event Design

| Event | Summary | Description | Payload | Trigger |
|-------|---------|-------------|---------|---------|
| `EventA` | <summary> | <description> | <fields> | <command accepted> |

## Field Capability Interfaces

| Interface | Field | Type | Used By | Reason |
|-----------|-------|------|---------|--------|
| `<FieldName>Capable` | `fieldName` | `<Type>` | <commands/events/state> | <shared domain meaning> |

## State Model

State is maintained through event sourcing. All field changes are driven by `@OnSourcing` handlers.

| Field | Type | Default | Source Events |
|-------|------|---------|---------------|
| `fieldA` | `<Type>` | `<default>` | `EventA` |

## Aggregate Behavior Tests

| Scenario | Given | When | Expected | Status |
|----------|-------|------|----------|--------|
| <scenario> | <events or state> | <command> | <event, state, or error> | Done |

## Saga Orchestration

Include this section only when the design has a saga.

```text
<Trigger event> -> <condition> -> <generated command or no command>
```

| Trigger | Condition | Target Aggregate | Command | Retry Policy |
|---------|-----------|------------------|---------|-----------------------|
| `EventA` | <condition> | <target id rule> | `CommandB` | <policy> |

## Saga Orchestration Tests

| Scenario | Given Event | Expected Command | Status |
|----------|-------------|------------------|--------|
| <scenario> | <event> | <command or no command> | Done |

## Appendix: File Index

| Layer | File | Description |
|-------|------|-------------|
| API | `path/to/api/File.kt` | <commands, events, contracts> |
| Domain | `path/to/domain/File.kt` | <aggregate, state, saga> |
| Test | `path/to/test/File.kt` | <behavior or orchestration tests> |
| Doc | `path/to/doc.md` | <scenario or design document> |
```

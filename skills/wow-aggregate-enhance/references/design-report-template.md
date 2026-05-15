# Aggregate Design Report Template

## File Location

`docs/aggregate-design/<AggregateName>-aggregate-design.md`

## Template

```markdown
# <AggregateName> Aggregate Design Report

## Overview

<One sentence describing the aggregate root's responsibility and core domain concept>.

### Aggregate ID Generation Rule

```
id = <components>
```

<Explanation of ID composition and uniqueness guarantee>.

---

## Command Design

| Command | Responsibility | Event | Use Case |
|---------|---------------|-------|----------|
| `CommandA` | Description | `EventA` | When to use |
| ... | ... | ... | ... |

### <Detailed per-command section>

**Fields:** <field list and source interface>

**Validation Rules:**
- <Rule 1>
- <Rule 2>

---

## Event Design

| Event | Payload | Trigger |
|-------|---------|---------|
| `EventA` | <field list> | <CommandA executed successfully> |
| ... | ... | ... |

---

## State Model

### <StateClassName>

State is maintained via event sourcing. All field changes are driven by `@OnSourcing` methods.

| Field | Type | Default | Source Events |
|-------|------|---------|---------------|
| `fieldA` | Type | default | EventA, EventB |
| ... | ... | ... | ... |

### Validity Rules (if any)

```kotlin
val isValid: Boolean
    get() = <condition>
```

---

## Saga Orchestration (if any)

### <SagaClassName>

```
<Trigger event> → <Processing logic> → <Generated command>
```

**Design Notes:**
- <Point 1>
- <Point 2>

---

## Interface Hierarchy

```
<Interface inheritance diagram>
```

- `<InterfaceA>` — Responsibility
- `<InterfaceB>` — Responsibility

---

## Test Scenarios

See `document/test-cases/<AggregateName>TestCases.md` for details. Covers <N> command/event handlers, <M> scenarios total.

| Handler | Scenarios | Coverage |
|---------|-----------|----------|
| `onXxx` | N | ... |

---

## Appendix: File Index

| Layer | File | Description |
|-------|------|-------------|
| API | `api/.../Xxx.kt` | Description |
| Domain | `domain/.../Xxx.kt` | Description |
| Test | `domain/.../XxxSpec.kt` | Description |
| Doc | `document/test-cases/XxxTestCases.md` | Description |
```

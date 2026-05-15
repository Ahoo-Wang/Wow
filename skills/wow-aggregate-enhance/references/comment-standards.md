# KDoc Comment Standards (Wow Aggregates)

## Core Principles

- Use the project's comment language (Chinese for this project).
- Comment **why**, not what — don't annotate self-explanatory code.
- Use Kotlin KDoc reference syntax: `[ClassName]` / `[ClassName.methodName]`.
- Don't comment trivial getters/setters.

---

## API Layer Conventions

### Interface

**Class-level KDoc** — describe purpose, inheritance, and core contract:

```kotlin
/**
 * [Interface description].
 *
 * [Core responsibility].
 *
 * ## Special rules (if any)
 * [Key algorithm or computation logic]
 */
interface IXxxId : Identifier, ... { ... }
```

**Property comments** — state business meaning, units, and constraints:

```kotlin
/** Unit cost (in yuan), rounded to 2 decimal places */
@get:Schema(title = "Unit Cost", description = "Unit cost price")
val costPrice: BigDecimal

/** Expiry date (Unix timestamp, seconds). Cost is invalid after this time. */
@get:Schema(title = "Expiry Date", description = "Expiry date")
val expiryDate: Long
```

**Computed property comments** — explain the derivation logic:

```kotlin
/**
 * Whether the cost is currently valid.
 *
 * Valid when: not disabled AND expiryDate > current time.
 * Computed property, read-only.
 */
val isValid: Boolean
    get() = !disabled && expiryDate > System.currentTimeMillis()
```

### Command

**Class-level KDoc** — must include: responsibility, use cases, aggregate ID composition, special notes:

```kotlin
/**
 * [Command description].
 *
 * [Responsibility].
 *
 * ## Use cases
 * [When to use this command]
 *
 * ## Aggregate ID
 * Composed of [fields].
 */
data class SaveXxx(...) : IXxxInfo, CommandValidator { ... }
```

**Field comments** — note business meaning, constraints, and special behavior:

```kotlin
/** Minimum order quantity, must be > 0 */
@field:Positive(message = "MOQ must be > 0")
override val moq: Int

/** Modification timestamp (milliseconds). Used for idempotency — updates with older modifiedTime are ignored. */
override val modifiedTime: Long = System.currentTimeMillis()
```

### Event

**Class-level KDoc** — describe trigger condition:

```kotlin
/**
 * [Event description].
 *
 * Triggered when: [Command] executes successfully.
 */
data class XxxSaved(...) : IXxxInfo
```

---

## Domain Layer Conventions

### Aggregate Root

**Class-level KDoc** — must include: core responsibility, idempotency mechanism (if any), soft-delete mechanism (if any):

```kotlin
/**
 * [Aggregate description].
 *
 * Manages the full lifecycle of [domain concept]: [operation list].
 *
 * ## Idempotency protection
 * Implemented via [modifiedTime]: if the new command's modifiedTime < current record's modifiedTime,
 * the existing data is returned unchanged, preventing stale data from overwriting newer data.
 *
 * ## Soft delete
 * Via [RemoveXxx] command, sets disabled = true. Disabled costs can be reactivated by re-saving.
 */
class Xxx(private val state: XxxState) { ... }
```

**@OnCommand method comments** — must include: method description, business rules (list format), params, return, exceptions (if any):

```kotlin
/**
 * [Method description].
 *
 * Business rules:
 * - [Rule 1]
 * - [Rule 2]
 *
 * @param command [Command name]
 * @return [Event name]
 * @throws IllegalArgumentException if [error condition]
 */
@OnCommand
fun onSaveXxx(command: SaveXxx): XxxSaved { ... }
```

### State

**Class-level KDoc** — describe event sourcing pattern:

```kotlin
/**
 * [Aggregate name] state.
 *
 * Maintains internal state via event sourcing. All field changes are driven by [OnSourcing] methods.
 */
class XxxState : IXxxState { ... }
```

**Field comments** — note default values and how state changes:

```kotlin
/** Whether disabled. Soft delete — can be reactivated via [SaveXxx]. */
override var disabled: Boolean = false
```

**@OnSourcing method comments** — document trigger source and field changes:

```kotlin
/**
 * Handles [EventName] event.
 *
 * Triggered by [Xxx.onSaveXxx].
 * Sets all business fields and ensures disabled = false.
 */
@OnSourcing
private fun onXxxSaved(domainEvent: DomainEvent<XxxSaved>) { ... }
```

### Saga

**Class-level KDoc** — describe the event listener and generated commands:

```kotlin
/**
 * [Saga name].
 *
 * Listens for [Event] → generates [Command], achieving [business purpose].
 */
@StatelessSaga
class XxxSaga { ... }
```

**@OnStateEvent/@OnEvent method comments** — document trigger conditions, business decisions, and output commands:

```kotlin
/**
 * Responds to [EventName] event.
 *
 * Trigger condition: [condition]
 * Business logic: [logic description]
 *
 * @return List of [CommandName] commands generated
 */
@OnStateEvent
private fun onEvent(
    event: SomeEvent,
    state: ReadOnlyStateAggregate<SomeState>
): List<CommandBuilder> { ... }
```

---

## Common Patterns

| Scenario | Comment Focus |
|----------|--------------|
| Migration/deprecated command | Mark as "only used for legacy data migration, not exposed externally" |
| Idempotency protection | Explain modifiedTime comparison logic |
| Soft delete | Explain disabled flag and reactivation mechanism |
| Date truncation | Explain truncation to calendar day boundary |
| Precision handling | Explain BigDecimal rounding mode |
| Unchecked condition | Explicitly state "does not check X state" |

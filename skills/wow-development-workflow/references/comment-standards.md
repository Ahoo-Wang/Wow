# Code Comment Standards

## Core Principles

- Write source-code comments and API documentation in English. User-facing documentation follows the language of the page.
- Comment why, not what. Do not annotate self-explanatory code.
- Treat comments as maintained code: update or remove them whenever the behavior changes.
- Do not comment trivial getters, setters, or obvious field assignments.
- Do not keep disabled code in comments; rely on version control.
- Use Wow `@Summary` and `@Description` on commands and domain events. These annotations feed schema/API metadata and are not a replacement for KDoc.

## Comment Forms

- Use KDoc for non-obvious public Kotlin contracts and Javadoc for non-obvious public Java contracts. Document semantics, constraints, units, failure modes, concurrency, compatibility, or usage—not the declaration's spelling.
- Use `//` for implementation rationale, invariants, compatibility constraints, and non-obvious edge cases.
- Reserve block comments for KDoc, Javadoc, license headers, or explanations that genuinely need multiple paragraphs.
- In Kotlin KDoc, use Markdown and links such as `[ClassName]` or `[ClassName.methodName]`; do not use Javadoc HTML tags.
- Keep `@param`, `@property`, `@return`, and `@throws` tags accurate. Omit tags that add no information beyond the signature.
- Do not commit inline task markers. Track deferred work in the issue tracker and use `Issue #1234: explain the constraint.` only when the code needs that context.
- Preserve the existing Apache 2.0 header. New hand-written source files copy the header used by neighboring files.
- Never hand-edit generated comments. Fix the schema, metadata, template, or generator input instead.

Tests and examples may use short phase comments when they make a scenario easier to scan, but should not narrate every assertion or mock setup. Performance claims belong beside a reproducible benchmark or must identify the benchmark, environment, and evidence that support them.

## Automated Checks

- Run `./gradlew detekt` to check Kotlin KDoc consistency and reject inline task markers.
- Run `pnpm lint` in `compensation/dashboard` to check comment spacing and reject inline task markers in hand-written dashboard sources.
- Review comment language, accuracy, and rationale during code review. These semantic requirements are deliberately not enforced by natural-language heuristics.

## API Layer

### Interface

Class-level KDoc should describe purpose, inheritance, and the core contract.

```kotlin
/**
 * Describes the domain identity contract.
 *
 * Combines the identifier fields required to route commands to the aggregate.
 */
interface IXxxId : Identifier
```

Property comments should state business meaning, units, and constraints.

```kotlin
/** Unit cost in yuan, rounded to 2 decimal places. */
val costPrice: BigDecimal
```

When an important field appears repeatedly in commands, events, state, or read contracts, extract a dedicated capability interface:

```kotlin
interface CostPriceCapable {
    @Summary("Cost price")
    @Description("Unit cost price in yuan, rounded to 2 decimal places.")
    val costPrice: BigDecimal
}
```

Computed property comments should explain derivation logic.

```kotlin
/**
 * Whether the value is currently valid.
 *
 * Valid when it is not disabled and the expiry time is in the future.
 */
val isValid: Boolean
    get() = !disabled && expiryDate > System.currentTimeMillis()
```

### Command

Command KDoc should include responsibility, use case, aggregate id composition, and special idempotency or routing notes.

```kotlin
@Summary("Save record")
@Description(
    """Creates or updates the record.
The aggregate id is composed from tenant id and business id.
Older updates are ignored by modifiedTime."""
)
/**
 * Saves the domain record.
 *
 * Used when the upstream system creates or updates the record.
 *
 * ## Aggregate ID
 * Composed from tenant id and business id.
 */
data class SaveXxx(...)
```

For long descriptions, use Kotlin raw string syntax directly in the annotation. Do not use `.trimIndent()` in annotation arguments.

Field comments should explain constraints and special behavior.

```kotlin
/** Modification timestamp in milliseconds. Older updates are ignored. */
val modifiedTime: Long
```

### Event

Event KDoc should describe the committed fact and trigger.

```kotlin
@Summary("Record saved")
@Description("The record was accepted and persisted by the aggregate.")
/**
 * Record saved.
 *
 * Triggered when [SaveXxx] is accepted by the aggregate.
 */
data class XxxSaved(...)
```

## Domain Layer

### Aggregate Root

Class-level KDoc should describe the aggregate responsibility, lifecycle, idempotency, and soft-delete behavior when present.

```kotlin
/**
 * Manages the lifecycle of the domain record.
 *
 * The aggregate accepts create and update commands, emits durable facts, and
 * protects stale writes through [modifiedTime].
 */
class Xxx(private val state: XxxState)
```

Command handler comments should name business rules, parameters, return event, and exceptions.

```kotlin
/**
 * Handles [SaveXxx].
 *
 * Business rules:
 * - Rejects stale updates.
 * - Reactivates a disabled record when the new update is accepted.
 *
 * @return [XxxSaved]
 */
@OnCommand
fun onCommand(command: SaveXxx): XxxSaved
```

### State

State KDoc should make the event-sourcing rule explicit.

```kotlin
/**
 * Event-sourced state for [Xxx].
 *
 * All mutable fields are changed only by `@OnSourcing` handlers.
 */
class XxxState
```

Sourcing handler comments should name the event and the fields it owns.

```kotlin
/**
 * Applies [XxxSaved].
 *
 * Updates all business fields and clears the disabled flag.
 */
@OnSourcing
fun onSourcing(event: XxxSaved)
```

### Saga

Saga KDoc should describe the process policy, trigger event, and generated commands.

```kotlin
/**
 * Coordinates the downstream update after [XxxSaved].
 *
 * When the trigger condition is met, the saga emits [UpdateOtherAggregate].
 */
@StatelessSaga
class XxxSaga
```

Saga handler comments should document trigger conditions, branch behavior, generated command, and no-command cases.

```kotlin
/**
 * Reacts to [XxxSaved].
 *
 * Emits [UpdateOtherAggregate] only when the downstream aggregate must be
 * synchronized. Returns no command when the event does not affect it.
 */
@OnEvent
fun onEvent(event: XxxSaved): CommandBuilder?
```

## Common Patterns

| Scenario | Comment Focus |
|----------|---------------|
| Legacy command | Mark whether it is migration-only or still part of normal behavior. |
| Idempotency | Explain the comparison key and stale-write behavior. |
| Soft delete | Explain whether later commands can reactivate the aggregate. |
| Date truncation | Explain the business boundary such as day, month, or timezone. |
| Precision | Explain scale and rounding mode. |
| Unchecked condition | State intentionally unchecked assumptions. |

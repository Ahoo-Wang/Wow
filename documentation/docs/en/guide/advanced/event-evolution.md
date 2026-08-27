---
title: Event Evolution
description: Safely evolve persisted domain events with event revisions, EventUpgrader, and historical replay tests.
outline: deep
---

# Event Evolution

Once persisted, a domain event becomes a long-lived data contract. Changing a Kotlin class does not change historical records. Before deserialization, Wow invokes `EventUpgrader` to transform old records into a shape the current model can read.

```text
raw EventStore record → EventUpgrader chain → current event type → @OnSourcing
```

An upgrade changes the in-memory record used for that read. It does not rewrite the authoritative event store in place.

## Revision Is Not Aggregate Version

- **Event `revision`** describes the event payload schema and is declared with `@Event(revision = "...")`; its default is `0.0.1`.
- **Aggregate `version`** is the event's position in one aggregate stream and supports replay and optimistic concurrency.

When fields are added, removed, renamed, or retyped, change the event revision. Do not use aggregate version to represent a schema change.

## When an Upgrader Is Required

| Change | Direction |
| --- | --- |
| Add an optional field with a safe default | It may remain compatible, but still run historical deserialization tests |
| Add a required field or change a field type or nested shape | Add an `EventUpgrader` |
| Rename an event or JVM type | Upgrade `name`, `bodyType`, and `body` together |
| Event no longer has business meaning | Convert to `DroppedEvent` only when replay semantics permit it |
| Repair incorrect historical business data | Do not disguise it as schema evolution; design separate audit, backup, reconciliation, and rollback controls |

## Example: Add a Field to an Old Event

Assume `order_created` in aggregate `sales.order` moves from `0.0.1` to `2.0.0`, and the current type requires `currency`:

```kotlin
@Event(revision = "2.0.0")
data class OrderCreated(
    val customerId: String,
    val totalAmount: BigDecimal,
    val currency: String,
)
```

Create an upgrader. `EventUpgraderFactory` invokes every registered upgrader for the same event, so each upgrader must check its own source revision. Without that guard, it would also transform already-upgraded and newly written events.

```kotlin
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.event.upgrader.EventNamedAggregate
import me.ahoo.wow.event.upgrader.EventNamedAggregate.Companion.toEventNamedAggregate
import me.ahoo.wow.event.upgrader.EventUpgrader
import me.ahoo.wow.event.upgrader.MutableDomainEventRecord.Companion.toMutableDomainEventRecord
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.serialization.event.DomainEventRecord

@Order(100)
class OrderCreatedV2Upgrader : EventUpgrader {
    override val eventNamedAggregate: EventNamedAggregate =
        "sales.order"
            .toNamedAggregate()
            .toEventNamedAggregate("order_created")

    override fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord {
        if (domainEventRecord.revision != "0.0.1") {
            return domainEventRecord
        }

        return domainEventRecord.toMutableDomainEventRecord().apply {
            body.put("currency", "CNY")
            revision = "2.0.0"
        }
    }
}
```

`sales.order` and `order_created` must exactly match the context, aggregate, and event names in storage. Do not infer them from a new class name; confirm them from the selected release's metadata or real historical records.

## Register with ServiceLoader

Create the following file in the runtime module containing the upgrader:

```text
src/main/resources/META-INF/services/me.ahoo.wow.event.upgrader.EventUpgrader
```

List one fully qualified implementation class per line:

```text
com.example.order.event.OrderCreatedV2Upgrader
```

Wow loads these implementations through Java `ServiceLoader` when `EventUpgraderFactory` initializes. The registration file must be present on the final runtime classpath; rebuild and restart after changing it.

## Chained Upgrades and Ordering

A long-lived system commonly needs multiple steps:

```text
0.0.1 --Order(100)--> 1.0.0 --Order(200)--> 2.0.0
```

- a lower `@Order` runs first;
- every step accepts one explicit source revision and emits one explicit target revision;
- do not grow one function into a branch for every historical version;
- do not remove an old step while that revision can still be read.

The framework's ServiceLoader and ordering evidence is in [`EventUpgraderFactoryTest`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/test/kotlin/me/ahoo/wow/event/upgrader/EventUpgraderFactoryTest.kt).

## Rename or Drop an Event

`MutableDomainEventRecord` can change `name`, `bodyType`, `revision`, and `body`. When renaming, keep all four consistent with the target event type and run a full replay test.

An event that truly no longer participates in current state can become `DroppedEvent`:

```kotlin
import me.ahoo.wow.event.upgrader.DroppedEvent.toDroppedEventRecord

override fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord {
    if (domainEventRecord.revision != "0.0.1") {
        return domainEventRecord
    }
    return domainEventRecord.toDroppedEventRecord()
}
```

::: danger Dropping changes replay semantics
Drop an event only when all later state and invariants are independent of it. Dropping merely to suppress a deserialization failure can produce an apparently successful but incorrect aggregate state.
:::

## Required Test Evidence

Cover at least three boundaries:

1. **Single-step transformation**: use a real old-revision record and verify fields, event name, type, and target revision;
2. **Registration and order**: prove `EventUpgraderFactory.get(...)` discovers the implementations in `@Order` sequence;
3. **Historical replay**: rebuild aggregates from sanitized production samples or complete historical fixtures and compare final state and critical invariants.

A unit test that calls only the upgrader function does not prove ServiceLoader registration, chain order, or real event deserialization.

## Release and Rollback Gates

1. Back up the event store and prove the backup can be restored.
2. Inventory revision counts and malformed records in an isolated environment.
3. Replay representative aggregates and the longest streams with the candidate code.
4. Compare aggregate state, projections, and critical business totals before and after the upgrade.
5. Before a rolling deployment, decide whether old instances can read the new revision; otherwise stop writes or use a compatible staged rollout.
6. Retain the previous application and upgrader chain as a rollback path, and prove rollback will not encounter events it cannot understand.

A local replay is not production cutover evidence. Revision distribution, mixed-version deployment windows, and restore evidence must be verified independently.

## Source References

- [`DomainEventRecord.toDomainEvent`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/DomainEventRecord.kt)
- [`EventUpgrader`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/upgrader/EventUpgrader.kt)
- [`EventUpgraderFactory`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/upgrader/EventUpgraderFactory.kt)
- [`MutableDomainEventRecord`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/upgrader/MutableDomainEventRecord.kt)
- [`DroppedEvent`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/upgrader/DroppedEvent.kt)

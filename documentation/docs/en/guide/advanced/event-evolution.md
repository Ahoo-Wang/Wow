---
title: Event Evolution
description: Maintain persisted-event wire contracts with revision, EventUpgrader, and historical replay.
outline: deep
---

# Event Evolution

A persisted domain event is a long-lived wire contract. Changing a Kotlin class affects new code; historical JSON retains its old event name, type, revision, and body. Before a `DomainEventRecord` becomes a current event object, Wow invokes `EventUpgraderFactory`:

```text
EventStore record
  → locate upgraders by context + aggregate + event name
  → transform the record in @Order sequence
  → resolve the current event type by type id + revision
  → @OnSourcing
```

An upgrader changes the `ObjectNode` used for this read. It does not write the transformed record back to EventStore.

## Two kinds of version

| Field | Meaning | Use |
| --- | --- | --- |
| `revision` | Event-body schema revision declared by `@Event(revision = ...)`, default `0.0.1` | Type resolution and explicit upgrade logic |
| Aggregate `version` | Position of an event stream in one aggregate history | Ordering, restoration, and optimistic concurrency |

Do not express schema evolution by changing aggregate version. An event revision also does not tell the framework how to transform data; every `EventUpgrader` is an explicit application function.

## Decide whether an upgrader is required

| Change | Required evidence |
| --- | --- |
| Add an optional field or safe default | Prove the current mapper reads real old records and replay preserves state |
| Add a required field, change a type, or reshape nesting | Add an upgrader that converts the old body to the target shape |
| Rename an event or JVM type | Evaluate `name`, `bodyType`, `revision`, and body together; verify type registration |
| Event no longer affects current state | Convert to `DroppedEvent` only after proving replay invariants are unchanged |
| Repair an incorrect business fact | Use an auditable data repair or compensation; do not disguise it as harmless schema evolution |

## Implement one upgrade step

```kotlin
@Order(100)
class OrderCreatedV2Upgrader : EventUpgrader {
    override val eventNamedAggregate =
        "sales.order".toNamedAggregate()
            .toEventNamedAggregate("order_created")

    override fun upgrade(record: DomainEventRecord): DomainEventRecord {
        if (record.revision != "0.0.1") return record

        return record.toMutableDomainEventRecord().apply {
            body.put("currency", "CNY")
            revision = "2.0.0"
        }
    }
}
```

`EventUpgraderFactory` executes **every** registered upgrader for that event. It does not skip a step based on revision. Each step must therefore recognize its own source revision and write an explicit target revision. `eventNamedAggregate` must exactly match the context, aggregate, and event name stored in history.

## Registration and ordering

Put a ServiceLoader file on the final runtime classpath:

```text
META-INF/services/me.ahoo.wow.event.upgrader.EventUpgrader
```

List one implementation class per line:

```text
com.example.order.event.OrderCreatedV2Upgrader
```

The factory loads implementations during initialization and sorts them with Wow `@Order`. A multi-step chain can be represented as:

```text
0.0.1 --order 100--> 1.0.0 --order 200--> 2.0.0
```

The framework only runs the ordered function list. Continuity, missing revisions, and valid output remain application test responsibilities. Keep every step while history may still contain its source revision.

## Rename or drop an event

`MutableDomainEventRecord` can change `name`, `bodyType`, `revision`, and body. After a rename, the target `(context, aggregate, name, revision)` must resolve to the expected event type; changing only the Kotlin class name is insufficient.

`toDroppedEventRecord()` changes `bodyType`, `name`, and body to the framework's dropped record while retaining aggregate version and sequence in the stream. It does not delete history or prove that the event has no state effect.

::: danger
Dropping an event merely to bypass deserialization can produce an apparently successful replay with incorrect state. First prove that every later state and business invariant is independent of the event.
:::

## Verification matrix

1. **Function:** source revision input, fields, target revision, name, type, and body for each step.
2. **Registration:** the final artifact loads implementations through ServiceLoader in the expected `@Order`.
3. **Deserialization:** upgraded records resolve through `EventTypeRegistry` to the intended type.
4. **Historical replay:** restore aggregates from sanitized production samples or complete fixtures and compare critical state and invariants.
5. **Downstream:** verify projections, Sagas, and BI process old/new events consistently or according to migration design.

The narrow repository checks are:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.event.upgrader.EventUpgraderFactoryTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.serialization.JsonSerializerEventTest"
```

## Release and rollback

- Count real revisions and prove backup restore before release.
- Replay representative aggregates and the longest streams in isolation.
- If old and new instances coexist during rollout, prove old instances can read newly written revisions.
- When bidirectional reading is impossible, design write suspension or staged compatibility instead of a direct rolling update.
- Keep the upgrader chain required by the application version used for rollback.

Passing local tests proves candidate code only. It does not prove production revision distribution, coexistence windows, or recovery procedures.

## Source and related pages

- [`DomainEventRecord`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/DomainEventRecord.kt)
- [`EventUpgraderFactory`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/upgrader/EventUpgraderFactory.kt)
- [`MutableDomainEventRecord`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/upgrader/MutableDomainEventRecord.kt)
- [Serialization](./serialization.md): mapper and event-type resolution
- [Migration](../migration.md): release, reconciliation, and rollback boundaries

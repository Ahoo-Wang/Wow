---
title: Serialization
description: Wow's Jackson 3 mapper, framework module, event-type resolution, and wire-compatibility boundary.
outline: deep
---

# Serialization

Wow uses Jackson 3 for commands, event streams, state events, snapshots, and state aggregates. Serialization configuration may affect HTTP, messaging, and persisted data at the same time, so a mapper is not merely an internal implementation detail.

## Two entry points

| Entry point | Owner | Use |
| --- | --- | --- |
| `JsonSerializer` | A preconfigured global `ObjectMapper` in `wow-core` | Wow internals and application conversion helpers |
| `WowModule` | A module that can be registered with any Jackson mapper | Spring Boot's mapper or an application-built mapper |

`JsonSerializer` is built with Kotlin `jsonMapper`. It configures field visibility, ignores unknown properties, allows final-field mutation, materializes untyped floating numbers as `BigDecimal`, and calls `findAndAddModules()`. It is not equivalent to a bare `ObjectMapper` created by the application.

Common helpers delegate to that mapper:

```kotlin
val json = order.toJsonString()
val decoded = json.toObject<Order>()
val tree = json.toObjectNode()
val copied = order.deepCopy()
```

## Framework formats owned by WowModule

`WowModule` registers serializers and deserializers for:

- `AggregateId`
- `CommandMessage`
- `DomainEventStream` and `DomainEvent`
- `StateAggregate`
- `Snapshot`
- `StateEvent`

It also installs `MissingTypeImplProblemHandler`. The Spring Boot Starter provides a `WowModule` bean before Jackson auto-configuration. That adds the Wow module but does not copy all `JsonSerializer` features into the Spring-managed mapper.

An application that replaces the Spring mapper, disables module discovery, or builds a mapper itself must explicitly register the required Kotlin modules and `WowModule`, then test the actual runtime path.

## Event-record type resolution

A persisted event record stores both stable business identity and a JVM type hint, including context, aggregate, event `name`, `revision`, `bodyType`, and body.

Deserialization proceeds as follows:

1. `EventUpgraderFactory` transforms an old record.
2. `EventTypeRegistry` looks up a current metadata type using `(context, aggregate, name, revision)`.
3. If not found, deserialization attempts the stored `bodyType`.
4. If that class is also unavailable, the record remains a `JsonDomainEvent` whose body is a JSON tree.

This fallback preserves a representation for an unknown historical type; it does not prove that a current aggregate can replay it correctly. If sourcing depends on a concrete type, supply a resolvable type or an [Event Upgrader](../domain/event-evolution.md).

When a `DomainEventStream` is deserialized, event `sequence` and `isLast` are derived again from body-list position. Do not treat custom JSON property order outside that array as an event-order contract.

## Missing polymorphic type fallback

`@MissingTypeImpl` declares a default subtype only when JSON is **missing** its type ID:

```kotlin
@MissingTypeImpl(Expression.Field::class)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface Expression
```

The fallback runs only when the mapper has `MissingTypeImplProblemHandler` registered.

| Input | Behavior |
| --- | --- |
| Missing type, annotated base type | Construct the annotated subtype |
| Known type | Use normal Jackson subtype resolution |
| Unknown type | The handler does not intercept; retain the mapper's unknown-type policy |
| Missing type, unannotated base type | Retain Jackson's missing-type error |

The annotation is not a blanket old-JSON compatibility switch. Its implementation must be a valid subtype of the current base type, and this runtime fallback does not make an OpenAPI/JSON Schema discriminator optional.

## Assess three compatibility scopes separately

| Scope | Example | Required verification |
| --- | --- | --- |
| Source | Kotlin property or constructor changes | Recompile callers |
| Binary | Existing compiled callers load new classes/JARs | Binary compatibility check or real consumer execution |
| Wire | JSON fields, types, revisions, or defaults change | Historical event/snapshot/message/HTTP contract tests |

A successful Kotlin compilation does not prove historical events and snapshots are readable. Reading an old event does not prove an old binary can load. Implement only the compatibility scope the release requires.

## Minimum checks before customizing a mapper

1. Read real or sanitized historical events and snapshots with the final runtime mapper.
2. Round-trip known commands, event streams, StateEvents, and Snapshots.
3. Test missing, known, and unknown polymorphic type IDs.
4. Test EventTypeRegistry resolution and `bodyType` fallback.
5. Replay a complete aggregate and assert final state, not only JSON text.
6. Verify generated OpenAPI/Schema separately.

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.serialization.JsonSerializerMapperTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.serialization.JsonSerializerEventTest"
./gradlew :wow-api:test --tests "me.ahoo.wow.api.serialization.MissingTypeImplProblemHandlerTest"
```

## Source and related pages

- [`JsonSerializer`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/JsonSerializer.kt)
- [`WowModule`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/WowModule.kt)
- [`DomainEventRecord`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/event/DomainEventRecord.kt)
- [`MissingTypeImpl`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-api/src/main/kotlin/me/ahoo/wow/api/serialization/MissingTypeImpl.kt)
- [JSON Schema](./schema.md) / [OpenAPI](../open-api.md): generated-contract boundaries

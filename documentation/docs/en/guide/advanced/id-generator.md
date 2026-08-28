---
title: ID Generator
description: Separate message-ID and aggregate-ID generation, SPI selection, caching, and deployment responsibility.
outline: deep
---

# ID Generator

Wow uses CosId through two ID paths: global IDs for runtime envelopes and aggregate IDs selected per named aggregate. They share infrastructure but have different selection and cache boundaries.

## Two paths

| Path | API | Main use | Selection key |
| --- | --- | --- | --- |
| Global ID | `generateGlobalId()` / `GlobalIdGenerator` | Message IDs for commands, domain events, event streams, waits, and compensation records | System property `wow.cosid`, defaulting to CosId's `cosid` name |
| Aggregate ID | `NamedAggregate.generateId()` / `AggregateIdGeneratorRegistrar` | Create an aggregate ID when the command does not supply one | Aggregate metadata `id`, otherwise aggregate name |

Do not use message IDs and business aggregate IDs interchangeably. A domain with an existing natural ID can supply it explicitly; Wow does not require every aggregate to use one generated identifier format.

## Global generator

`GlobalIdGenerator` is a lazy singleton. On first access it loads `GlobalIdGeneratorFactory` implementations through Java `ServiceLoader`, sorts them with Wow `@Order`, and selects the first factory returning a non-null `CosIdGenerator`. If none can create one, access fails with `NotInitializedGlobalIdGeneratorError`.

The built-in `CosIdGlobalIdGeneratorFactory` looks up its configured name in `IdGeneratorProvider`. A custom factory implements the interface and registers through:

```text
META-INF/services/me.ahoo.wow.id.GlobalIdGeneratorFactory
```

Do not create a new global generator per request; selection happens once at lazy initialization.

## Aggregate generator

`AggregateIdGeneratorRegistrar` caches a generator by materialized `NamedAggregate`. On first use it calls ordered `AggregateIdGeneratorFactory` instances and selects the first non-null result.

The built-in `CosIdAggregateIdGeneratorFactory` selects in this order:

1. read the aggregate's generator `id` from `META-INF/wow-metadata.json`;
2. use aggregate name when metadata has no `id`;
3. return the same-named generator from `IdGeneratorProvider` when present;
4. otherwise create `Radix62CosIdGenerator` with the global generator's machine ID and wrap it in `ClockSyncCosIdGenerator`.

That fallback depends on an available global generator and its machine ID. It does not allocate machine IDs for a deployment.

## Deployment responsibility

The selected CosId configuration owns algorithm details, clock handling, and machine-ID allocation. Wow only selects and invokes a generator. A multi-instance deployment must prove:

- every instance receives a machine ID valid for the selected CosId generator;
- restart, scale-out, and lease reclamation do not reuse an active instance's machine ID;
- clock rollback, provider unavailability, and missing configuration have acceptable startup/generation behavior;
- ID length and character set fit downstream database and API contracts.

A unit test showing non-blank or increasing IDs in one JVM does not prove cross-node uniqueness, global ordering, or production capacity. Use [Configuration](../configuration.md) and metadata for the selected CosId version for production setup.

## Custom selection

Add an `AggregateIdGeneratorFactory` only when aggregates genuinely need different formats or providers. A factory may return `null` for aggregates it does not own, allowing later factories to participate. Do not reproduce metadata's `id` mapping as a global branch table.

```kotlin
@Order(100)
class InvoiceIdGeneratorFactory : AggregateIdGeneratorFactory {
    override fun create(namedAggregate: NamedAggregate): IdGenerator? =
        if (namedAggregate.aggregateName == "invoice") invoiceGenerator else null
}
```

Register it through:

```text
META-INF/services/me.ahoo.wow.id.AggregateIdGeneratorFactory
```

Factories and the registrar can be accessed concurrently; a custom generator must satisfy the concurrency contract its callers need.

## Verification

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.id.GlobalIdGeneratorTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.id.AggregateIdGeneratorRegistrarTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.id.CosIdAggregateIdGeneratorFactoryTest"
```

The application must also test multi-instance collisions and restarts with a production-like machine-ID allocator. Repository unit tests do not provide that evidence.

## Source and related pages

- [`GlobalIdGenerator`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/id/GlobalIdGenerator.kt)
- [`AggregateIdGeneratorRegistrar`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/id/AggregateIdGenerator.kt)
- [`CosIdAggregateIdGeneratorFactory`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/id/CosIdAggregateIdGeneratorFactory.kt)
- [Compiler](./compiler.md): source of aggregate `id` metadata
- [Core Concepts](../core-concepts.md#bounded-context-and-aggregate-identity): complete AggregateId boundary

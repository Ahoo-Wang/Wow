---
title: Wow Compiler
description: KSP inputs, three output categories, runtime consumers, and verification boundaries for wow-compiler.
outline: deep
---

# Wow Compiler

`wow-compiler` is a set of KSP processors. It converts bounded-context and aggregate annotations into machine-readable metadata and Kotlin constants so runtime, query, and interface modules consume the same model declarations.

It does **not directly generate an OpenAPI document or HTTP routes**. Runtime/build components such as `wow-openapi` and `wow-webflux` consume compiler metadata and assemble their own outputs.

## Installation

```kotlin
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    ksp("me.ahoo.wow:wow-compiler")
}
```

Align the compiler with the application's Wow, Kotlin, and KSP versions. See [Existing Project](../existing-project.md) for dependency and capability selection instead of copying a complete Gradle setup from this explanation.

## Processors and outputs

Three `SymbolProcessorProvider` implementations are registered through ServiceLoader:

| Processor | Main input | Output | Consumers |
| --- | --- | --- | --- |
| `MetadataSymbolProcessor` | `@BoundedContext`, `@AggregateRoot`, and resolved commands/events | `META-INF/wow-metadata.json` | `MetadataSearcher`, aggregate parsing, route/Schema/OpenAPI components |
| `AggregatesMetadataProcessor` | A bounded context and aggregates in its package scope | `AggregatesMetadata.kt` in the same package | Type-safe aggregate metadata references |
| `QuerySymbolProcessor` | Aggregate state types and nested properties | `<StateType>Properties.kt` | Query conditions, sorting, and field references |

These are build outputs. Do not edit or commit them. Change annotations/domain types and rerun KSP.

## `META-INF/wow-metadata.json`

The resource records data by context name:

- context alias, description, and package scopes;
- aggregate name, aggregate type, and package scopes;
- optional static tenant ID and ID-generator name;
- command and event type-name sets.

`wow-metadata.json` does not store a state type separately. The command/state pairing appears only in the later generated `AggregatesMetadata.kt`, as the two type parameters of `aggregateMetadata<CommandType, StateType>()`.

`MetadataSymbolProcessor` writes it as an aggregating KSP output. At runtime, `MetadataSearcher` finds every resource with that name on the classpath and merges them. If a module's resource is absent, its aggregate/command/event contract is not reconstructed completely through reflection.

Inspect the application artifact, not only the source tree:

```bash
jar tf build/libs/<application>.jar | grep 'META-INF/wow-metadata.json'
```

The final path and JAR name belong to the application build; placeholders are not a fixed command to copy verbatim.

## `AggregatesMetadata.kt`

The generated object is placed in the bounded-context package and provides a typed value for each resolved aggregate:

```kotlin
object AggregatesMetadata {
    val OrderAggregateMetadata = aggregateMetadata<Order, OrderState>()
}
```

A single-class aggregate has the same command/state type; the composition pattern retains two different type parameters. This file is a convenient metadata reference, not a new public business API and not a replacement for `wow-metadata.json`.

## Query-property constants

`QuerySymbolProcessor` traverses state properties and continues into nested types that are not Kotlin/Java simple types:

```kotlin
object OrderStateProperties {
    const val ID = "id"
    const val SHIPPING_ADDRESS = "shippingAddress"
    const val SHIPPING_ADDRESS__CITY = "shippingAddress.city"
}
```

Constant names use uppercase snake case, nested constant names use `__`, and values use dotted paths. A set of already-added navigation entries stops repeated traversal. Constants reduce handwritten spelling errors; they do not prove a field or index exists in the selected storage backend.

## Compile-time and runtime failures

| Symptom | Check first |
| --- | --- |
| No `wow-metadata.json` | Whether the domain module applies KSP, contains resolvable annotations, and packages the resource |
| One aggregate is absent | Package scope, aggregate annotation, module dependency, and final classpath |
| Property constants are stale | Clean and rerun KSP; confirm current generated directories are used |
| Runtime cannot parse aggregate metadata | Merged classpath resources against actual aggregate classes |
| OpenAPI/route is absent | Verify metadata first, then `wow-openapi`/`wow-webflux` capability, conditions, and runtime registration |

Do not repair a missing generated directory by hand-writing metadata. The root cause is normally that KSP did not run, a module is not depended on, or output was not packaged.

## Verification

Compiler tests inspect all three output categories directly:

```bash
./gradlew :wow-compiler:test --tests "me.ahoo.wow.compiler.metadata.MetadataSymbolProcessorTest"
./gradlew :wow-compiler:test --tests "me.ahoo.wow.compiler.aggregate.metadata.AggregatesMetadataSymbolProcessorTest"
./gradlew :wow-compiler:test --tests "me.ahoo.wow.compiler.query.QuerySymbolProcessorTest"
```

Application acceptance must additionally inspect the final JAR and actual OpenAPI/routes. Compiler tests do not prove that the application requested the corresponding runtime capability.

## Source and related pages

- [`MetadataSymbolProcessor`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-compiler/src/main/kotlin/me/ahoo/wow/compiler/metadata/MetadataSymbolProcessor.kt)
- [`AggregatesMetadataProcessor`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-compiler/src/main/kotlin/me/ahoo/wow/compiler/aggregate/metadata/AggregatesMetadataProcessor.kt)
- [`QuerySymbolProcessor`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-compiler/src/main/kotlin/me/ahoo/wow/compiler/query/QuerySymbolProcessor.kt)
- [OpenAPI](../open-api.md): how metadata enters interface contracts
- [JSON Schema](./schema.md): schema-generation responsibility

---
title: JSON Schema
description: Automatic JSON Schema and OpenAPI Schema generation from Wow domain models using jsonschema-generator.
---

# JSON Schema

The Schema module automatically generates JSON Schema and OpenAPI Schema from Wow domain models (Commands, Events, Snapshots, AggregateIds, and query models).

Built on [jsonschema-generator](https://github.com/victools/jsonschema-generator), it integrates with Jackson annotations, Jakarta Validation, Swagger annotations, and Kotlin type system.

## Features

- Auto-generate JSON Schema from Command, Event, and Snapshot types
- Support for Jackson, Jakarta Validation, and Swagger annotations
- Kotlin-specific type handling (nullable, ranges, etc.)
- OpenAPI 3.x Schema output
- Joda Money type support

## Installation

Add the `wow-schema` dependency:

=== "Gradle (Kotlin)"

```kotlin
implementation("me.ahoo.wow:wow-schema")
```

## Usage

### Generate JSON Schema

```kotlin
val generator = SchemaGeneratorBuilder.standard()
    .build()

val schema: JsonSchema = generator.generateSchema(CreateOrder::class.java)
val jsonNode: ObjectNode = schema.toJsonNode()
```

### Generate OpenAPI Schema

```kotlin
val openApiBuilder = OpenAPISchemaBuilder(contextName)
val schema = openApiBuilder.build(CreateOrder::class.java)
```

## Supported Types

| Type | Schema Handling |
|------|----------------|
| `AggregateId` | String with UUID format |
| `DomainEventStream` | Event stream wrapper |
| `Map<K, V>` | Object with additional properties |
| `CharRange` / `IntRange` / `LongRange` | Range-based constraints |
| `CurrencyUnit` / `Money` | Joda Money types |
| Enums | String enum definitions |

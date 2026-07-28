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

`SchemaGeneratorBuilder` is configured through fluent property setters (there is
no `standard()` factory). Each builder builds one `com.github.victools.jsonschema.generator.SchemaGenerator`:

```kotlin
val generator = SchemaGeneratorBuilder()
    .build()

// victools SchemaGenerator.generateSchema returns JsonNode directly
val jsonNode: JsonNode = generator.generateSchema(CreateOrder::class.java)
```

The builder is pre-configured with the Wow modules (Jackson, Jakarta Validation,
Swagger2, Kotlin, Joda Money, Wow naming) and sensible defaults
(`openapi31 = true`, `DRAFT_7`, `PLAIN_JSON` preset). Override any property
before calling `build()`.

### Generate OpenAPI Schema

`OpenAPISchemaBuilder` produces OpenAPI `io.swagger.v3.oas.models.media.Schema`
references and collects them under `components/schemas`. Generate a single type
reference with `generateSchema(...)`; collect all referenced schemas with
`build()` (no arguments):

```kotlin
val openApiBuilder = OpenAPISchemaBuilder(defaultSchemaNamePrefix = "")
// Reference (or inline) schema for one type
val schema: Schema<*> = openApiBuilder.generateSchema(CreateOrder::class.java)
// All schemas accumulated so far, keyed by component name
val components: Map<String, Schema<*>> = openApiBuilder.build()
```

The first constructor argument is `defaultSchemaNamePrefix` (used by the
`SchemaNamingModule` to prefix component names), not a context name.

## Supported Types

Wow ships dedicated `TypedCustomDefinitionProvider` implementations and modules
that supply schemas for framework and Kotlin/Joda types:

| Type / Module | Schema Handling |
|------|----------------|
| `AggregateId`, `DomainEventStream`, `AggregatedFields`, query models | Loaded from bundled JSON Schema resources via `WowSchemaLoader` (complex objects, not flattened primitives) |
| `Map<K, V>` (`MapDefinitionProvider`) | Object with additional properties |
| `CharRange` / `IntRange` / `LongRange` (`KotlinModule`) | Object with `start` and `end` properties |
| `CurrencyUnit` (`JodaMoneyModule`) | String with `format: currency` |
| `Money` (`JodaMoneyModule`) | Structured object with `currency` and `amount` |
| Enums (Jackson) | String enum definitions (`FLATTENED_ENUMS_FROM_JSONVALUE`/`JSONPROPERTY`) |
| Nullable Kotlin types (`KotlinNullableCheck`) | `null` added to the type union |
| `@Summary` / `@Description` | Resolved into schema `title` / `description` metadata |

## How the OpenAPI Module Uses Schemas

The `wow-openapi` module wires schema generation into the OpenAPI spec via
`OpenAPIComponentContext`. At context startup, it builds an `OpenAPISchemaBuilder`
backed by a `SchemaGeneratorBuilder` and calls `generateSchema(type)` for every
command body, event payload, and snapshot state registered in the bounded context.
The resulting `io.swagger.v3.oas.models.media.Schema` instances populate the
OpenAPI `components/schemas` section, which the Swagger UI renders. TypeScript clients
can be generated from the spec using the [Fetcher](https://github.com/Ahoo-Wang/Fetcher) toolchain.

```kotlin
// Simplified wiring inside wow-openapi (OpenAPIComponentContext.of)
val schemaGeneratorBuilder = SchemaGeneratorBuilder().schemaVersion(SchemaVersion.DRAFT_2020_12)
val schemaBuilder = OpenAPISchemaBuilder(
    defaultSchemaNamePrefix = "",
    schemaGeneratorBuilder = schemaGeneratorBuilder,
)
// For each command/event type encountered while building routes:
val commandSchema: Schema<*> = schemaBuilder.generateSchema(CreateOrder::class.java)
```

You normally never call this directly — applying `wow-compiler` (KSP) and adding
`wow-spring-boot-starter` (with the `openapi-support` capability) plus `wow-openapi` to the
server is enough. Schema generation is automatic at runtime via `OpenAPIAutoConfiguration` in
the starter.

## Customizing the Generator

`SchemaGeneratorBuilder` exposes fluent property setters. Override any before
calling `build()`:

```kotlin
val generator = SchemaGeneratorBuilder()
    .schemaVersion(SchemaVersion.DRAFT_2020_12)   // default DRAFT_7
    .openapi31(false)                              // default true
    .customizer {                                  // add victools Option/Module tweaks
        it.without(Option.SCHEMA_VERSION_INDICATOR)
    }
    .build()
```

| Builder property | Default | Purpose |
|---|---|---|
| `openapi31` | `true` | Emit OpenAPI 3.1-compatible constructs (`nullable` as a type-union member) |
| `schemaVersion` | `DRAFT_7` | JSON Schema dialect keyword resolution |
| `optionPreset` | `PLAIN_JSON` | Which members to include (fields, getters, etc.) |
| `jacksonModule` | Wow Jackson module | Respect `@JsonProperty`/`@JsonIgnore`/enum flattening |
| `jakartaValidationModule` | enabled | Surface `@NotNull`/`@Size`/`@Min` as schema constraints |
| `swagger2Module` | enabled | Surface `@Schema` annotations as OpenAPI metadata |

## Framework Type Schemas

Framework types that cannot be derived by reflection (`AggregateId`,
`DomainEventStream`, query models) are bundled as JSON Schema resources under
`META-INF/wow-schema/<TypeName>.json` and loaded by `WowSchemaLoader`. This keeps
the generated schemas stable across Wow versions even when the in-memory
representation changes.

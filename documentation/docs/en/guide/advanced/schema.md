---
title: JSON Schema
description: Generate JSON Schema and OpenAPI components from domain types while keeping compile metadata and runtime query-model schemas separate.
---

# JSON Schema

Wow uses the word "schema" for different artifacts. They must not be treated as interchangeable:

| Artifact | Producer | Consumer | Purpose |
|---|---|---|---|
| `META-INF/wow-metadata.json` | `wow-compiler` KSP | `MetadataSearcher` | bounded contexts, aggregates, commands, events, routes |
| JSON Schema | `SchemaGeneratorBuilder` / bundled resources | validation, tooling, OpenAPI conversion | wire shape of a Java/Kotlin type |
| OpenAPI components | `OpenAPISchemaBuilder` | `RouterSpecs`, Springdoc, generators | schemas referenced by HTTP operations |
| Query-model schema | query schema sources + backend adapter | query resolver and `snapshot/schema` / `event/schema` routes | logical fields and backend-proven query capabilities |

KSP also generates `*Properties` path constants for aggregate state navigation. Those constants do not enumerate runtime backend capabilities and do not replace the query-model schema.

## Features

- Generates JSON Schema from command, event, snapshot, and application types.
- Respects Jackson, Jakarta Validation, Swagger, Kotlin, and Joda Money metadata.
- Supports OpenAPI 3.1-compatible nullable shapes.
- Uses stable bundled definitions for framework types whose wire contract should not depend on reflection internals.
- Converts accumulated definitions into OpenAPI `Schema` components and references.

Schema generation describes serialization shape. It does not register a route, create a database mapping, authorize a field, or prove that MongoDB/Elasticsearch can execute an operator.

## Installation

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-schema")
```

Applications normally receive it through the relevant Wow capabilities. Add it directly only when application code calls the builders or consumes their types.

## Usage

### Generate JSON Schema

`SchemaGeneratorBuilder` is a fluent builder; there is no `standard()` factory:

```kotlin
val generator = SchemaGeneratorBuilder().build()
val schema: JsonNode = generator.generateSchema(CreateOrder::class.java)
```

The default builder uses `SchemaVersion.DRAFT_7`, `OptionPreset.PLAIN_JSON`, and `openapi31 = true`. It installs Wow's Jackson, Jakarta Validation, Swagger2, Kotlin, Joda Money, naming, and framework modules. Call `build()` before reading `requiredTypeContent`.

Generation happens from runtime types and registered serializers. KSP's metadata JSON is not an input to this call.

### Generate OpenAPI Schema

`OpenAPISchemaBuilder.generateSchema(...)` returns a reference (or an inline schema when configured) and records all required definitions. Call `build()` with no arguments to collect components:

```kotlin
val builder = OpenAPISchemaBuilder(defaultSchemaNamePrefix = "example.")

val requestSchema: Schema<*> = builder.generateSchema(CreateOrder::class.java)
val components: Map<String, Schema<*>> = builder.build()
```

`defaultSchemaNamePrefix` prefixes component names; it is not a bounded-context selector. `definitionPath` defaults to `components/schemas`, and generated `$ref` values are rebased to that location.

Do not call `build()` after every type in an application pipeline. Let all route contributors request their schemas, then finish the component context once so references can be merged consistently.

## Supported Types

| Type / module | Schema behavior |
|---|---|
| `AggregateId`, messages, event streams, snapshots, state aggregates | bundled framework definitions loaded through `WowSchemaLoader` |
| `FilterExpression` | canonical v2 query schema bundled as `META-INF/wow-schema/FilterExpression.json` |
| `AggregationExpression`, `QuerySemanticType` | polymorphic schemas with explicit `type` discriminator |
| `Map<K, V>` | object with additional properties |
| `CharRange`, `IntRange`, `LongRange` | object with `start` and `end` |
| `CurrencyUnit` | string with `currency` format |
| `Money` | object containing currency and amount |
| Kotlin nullable types | null is included in the schema union |
| Jackson enums | flattened string enum where configured |
| `@Summary`, `@Description`, Swagger `@Schema` | title, description, discriminator, and composition metadata |

The canonical single/list/cursor/paged/count JSON files live under `schema/query/v2`. Only the `FilterExpression` type is copied into `wow-schema` as the custom framework definition used during reflective generation.

## How the OpenAPI Module Uses Schemas

`OpenAPIComponentContext.default(...)` creates a `SchemaGeneratorBuilder` using Draft 2020-12 for OpenAPI components. Route contributors ask the context for command, event, snapshot, state, query, response, header, and request-body schemas. `RouterSpecs` renders their references into OpenAPI 3.1 and merges the finished component maps.

```kotlin
val context = OpenAPIComponentContext.default(
    inline = false,
    defaultSchemaNamePrefix = currentContext.getContextAliasPrefix(),
)
val commandSchema = context.schema(CreateOrder::class.java)
```

Spring Boot's `OpenAPIAutoConfiguration` provides this context and a `WowOpenApiCustomizer` when Springdoc is present. The WebFlux runtime consumes the same route catalog, but the schema builder does not create Handler functions.

OpenAPI query publication has two static layers, followed by one runtime layer:

1. Generic component schemas define the canonical JSON shape of `FilterExpression`, `SingleQuery`, `ListQuery`, `CursorQuery`, `PagedQuery`, and `AggregationQuery`.
2. Each aggregate-specific request-body component references the appropriate generic schema and adds `x-wow-query-fields`. That extension references a static enum containing system fields plus fields inferred from the aggregate state by `JsonQuerySchemaSource`; it does not contain backend bindings or proven capabilities.
3. `GET /{aggregate}/snapshot/schema` and `GET /{aggregate}/event/schema` return the Snapshot and EventStream runtime `QueryModelSchemaMetadata` respectively after all configured query-schema sources are merged and the selected backend adapter resolves capabilities. The corresponding `/refresh` route refreshes that runtime view.

The static field extension makes aggregate fields available to OpenAPI tooling without changing the generic request JSON shape. It must not be presented as equivalent to the runtime schema.

Client generation is a later consumer. Fetcher or another generator reads the published OpenAPI document. Changing a Kotlin type, discriminator, component name, or route may change generated clients; KSP metadata generation itself does not generate those clients.

## Customizing the Generator

Override builder properties before `build()`:

```kotlin
val generator = SchemaGeneratorBuilder()
    .schemaVersion(SchemaVersion.DRAFT_2020_12)
    .openapi31(false)
    .customizer { config ->
        config.without(Option.SCHEMA_VERSION_INDICATOR)
    }
    .build()
```

| Builder property | Default | Effect |
|---|---|---|
| `openapi31` | `true` | OpenAPI 3.1-compatible nullable handling |
| `schemaVersion` | `DRAFT_7` | JSON Schema keyword dialect |
| `optionPreset` | `PLAIN_JSON` | baseline field/getter inclusion |
| `jacksonModule` | Wow Jackson module | Jackson names, ignores, enum values, order |
| `jakartaValidationModule` | enabled | Jakarta constraints |
| `swagger2Module` | enabled | Swagger schema annotations |
| `kotlinModule` | enabled | Kotlin nullability, required/read-only/write-only details |
| `jodaMoneyModule` | enabled | Joda Money wire types |
| `wowModule` | enabled | framework definitions and query discriminator handling |

Passing `null` disables an optional module. This changes generated contracts; cover custom settings with schema snapshots or focused assertions before publishing them.

## Framework Type Schemas

`WowSchemaLoader` reads `META-INF/wow-schema/{TypeName}.json`. Bundled files keep stable public shapes for framework wrappers whose internal class graph is not the wire contract.

This stability is scoped to the schema resource and its serialized contract. It does not promise source or binary compatibility for every implementation class. When a schema resource changes, validate:

1. `wow-schema` generation tests;
2. `wow-openapi` component and route snapshots;
3. actual JSON serialization/deserialization;
4. downstream client generation where that OpenAPI is consumed.

For aggregate fields, distinguish the reflective JSON shape from the runtime query model. A property can exist in JSON Schema yet lack `SORT`, `RANGE`, `FULL_TEXT`, or aggregation capability because the selected backend cannot prove a compatible physical binding.

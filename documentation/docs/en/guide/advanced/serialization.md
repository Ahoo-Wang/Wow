---
title: Serialization
description: Jackson 3 entry points, module registration, missing polymorphic type fallback, and compatibility boundaries in Wow.
---

# Serialization

Wow uses Jackson 3 for the JSON representation of commands, event streams, snapshots, state aggregates, and query models. The framework exposes two complementary entry points:

- `JsonSerializer`: a preconfigured global `ObjectMapper` used by the Wow runtime and application helper code.
- `WowModule`: a Jackson module that registers serializers, deserializers, and the missing-type handler for Wow framework types.

A serialization shape can be an HTTP, messaging, and persistence contract at the same time. Evaluate source, binary, and wire compatibility separately before replacing a mapper or changing type annotations.

## Choose an Entry Point

| Scenario | Recommended entry point | Registration behavior |
|---|---|---|
| Wow Spring Boot application | Inject Spring's `ObjectMapper` | The starter provides a `WowModule` bean automatically |
| Wow runtime or in-application conversion | `JsonSerializer` and its extensions | Kotlin and Wow Jackson modules are discovered automatically |
| Fully custom mapper | Register the Kotlin module and `WowModule` | Enables Kotlin and Wow type serialization support |
| `wow-api`-only consumer | Register the Kotlin module; optionally register `MissingTypeImplProblemHandler` | Supports Kotlin models and optional `@MissingTypeImpl` fallback |

A bare `ObjectMapper` or `JsonMapper` does not automatically inherit Kotlin or Wow configuration or missing-type fallback.

## JsonSerializer

`JsonSerializer` lives in `wow-core`. It is built with Kotlin's `jsonMapper` and applies these Wow defaults:

- Field visibility `ANY` for all accessors.
- Final fields may be used as mutators.
- Unknown JSON properties are ignored.
- Untyped floating-point values are read as `BigDecimal`.
- Undefined parser tokens are ignored.
- Jackson modules, including `WowModule`, are discovered through SPI.

The convenience extensions share that mapper:

```kotlin
import me.ahoo.wow.serialization.deepCopy
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toLinkedHashMap
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.serialization.toObjectNode
import java.math.BigDecimal

data class OrderView(val id: String, val amount: BigDecimal)

val source = OrderView("order-1", BigDecimal("12.50"))
val json = source.toJsonString()
val decoded = json.toObject<OrderView>()
val tree = json.toObjectNode()
val copied = decoded.deepCopy()
val properties = decoded.toLinkedHashMap()
```

| API | Purpose |
|---|---|
| `toJsonString()` / `toPrettyJson()` | Write compact or formatted JSON |
| `String.toObject<T>()` | Read JSON as a concrete type |
| `toJsonNode()` / `toObjectNode()` | Convert between values and Jackson's tree model |
| `convert<T>()` | Map values using Jackson property conversion |
| `deepCopy()` | Create a same-type copy through `convertValue` |
| `toLinkedHashMap()` | Convert a value to an insertion-ordered property map |

These functions use Wow's global mapper configuration. They are not equivalent to a mapper created by the caller.

## WowModule

`WowModule` registers dedicated serializers for:

- `AggregateId`
- `CommandMessage`
- `DomainEventStream` and `DomainEvent`
- `StateAggregate`
- `Snapshot`
- `StateEvent`

It also registers `MissingTypeImplProblemHandler`. Do not duplicate individual serializer registrations; install the module instead:

```kotlin
import me.ahoo.wow.serialization.WowModule
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

val mapper = jsonMapper {
    addModule(kotlinModule())
    addModule(WowModule())
}
```

### Automatic Registration

The Spring Boot starter contributes a `WowModule` bean before Jackson auto-configuration. `wow-core` also publishes the module through `META-INF/services/tools.jackson.databind.JacksonModule`, which is discovered by `JsonSerializer.findAndAddModules()`.

Spring's mapper keeps its Spring Boot feature configuration. `WowModule` adds Wow serializers, deserializers, and the handler; it does not copy every global feature from `JsonSerializer`.

Applications that replace Spring's `ObjectMapper` completely or disable module discovery must register the Kotlin module and `WowModule` themselves.

## Missing Polymorphic Type Fallback

`MissingTypeImpl` and `MissingTypeImplProblemHandler` are public APIs in `wow-api`. The annotation declares a default implementation; a missing Jackson type id uses it only when the handler is registered.

```kotlin
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.wow.api.serialization.MissingTypeImpl

@MissingTypeImpl(Expression.Field::class)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(Expression.Field::class, name = "FIELD"),
    JsonSubTypes.Type(Expression.Constant::class, name = "CONSTANT"),
)
sealed interface Expression {
    data class Field(val field: String) : Expression
    data class Constant(val value: Double) : Expression
}
```

Register the public handler directly:

```kotlin
import me.ahoo.wow.api.serialization.MissingTypeImplProblemHandler
import tools.jackson.databind.json.JsonMapper

val mapper = JsonMapper.builder()
    .addHandler(MissingTypeImplProblemHandler())
    .build()
```

This builder example demonstrates handler registration only. Callers must still install the Kotlin and other datatype modules their models require.

### Exact Semantics

| Input | Result |
|---|---|
| `{"field":"amount"}` | Deserializes as `Field` when the base has `@MissingTypeImpl` and the handler is registered |
| `{"type":"CONSTANT","value":10}` | Uses normal Jackson known-subtype resolution |
| `{"type":"UNKNOWN"}` | Is not handled; behavior follows the caller's `FAIL_ON_INVALID_SUBTYPE` setting |
| Missing type id on an unannotated base | Preserves Jackson's native missing-type error |

The handler overrides only `handleMissingTypeId`. It does not handle unknown type ids or modify any global Jackson feature.

`@MissingTypeImpl` is a direct contract and is not inherited through class or interface hierarchies. Annotate the concrete base type being deserialized. This avoids ambiguous interface precedence and prevents a parent default from being selected when it is not a subtype of the narrower base. The implementation must be a valid subtype or Jackson cannot construct the specialized type.

## AggregationExpression Compatibility Boundary

`AggregationExpression` declares `@MissingTypeImpl(AggregationExpression.Field::class)`. With `WowModule` or the explicit handler, the legacy payload may still omit the type id:

```json
{"field":"amount"}
```

New callers should send the explicit discriminator:

```json
{"type":"FIELD","field":"amount"}
```

A bare mapper rejects the first form. Because `JsonTypeInfo.defaultImpl` is no longer present, the generated OpenAPI schema also marks `type` as required. Runtime acceptance of legacy JSON does not make the generated contract describe `type` as optional.

## Persistence and Testing

Event streams and snapshots are long-lived wire contracts. Before changing a mapper, module, or type annotation, verify at least:

1. The production mapper can read representative historical events and snapshots.
2. Known polymorphic types survive a serialization round trip.
3. Required missing-type compatibility is tested through the actual registration path.
4. Unknown type ids still follow the application's failure policy instead of silently falling back.
5. JSON Schema and OpenAPI discriminators, required fields, and recursive references match the intended contract.

Related guides:

- [Event Evolution](./event-evolution)
- [JSON Schema](./schema)
- [Kafka Extension](../extensions/kafka)

Sources:

- [`JsonSerializer.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/JsonSerializer.kt)
- [`WowModule.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/serialization/WowModule.kt)
- [`MissingTypeImpl.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-api/src/main/kotlin/me/ahoo/wow/api/serialization/MissingTypeImpl.kt)
- [`SerializationAutoConfiguration.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/serialization/SerializationAutoConfiguration.kt)

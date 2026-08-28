# Wow API

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](../LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-api)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-api)

`wow-api` is Wow's public contract layer for commands, events, messages, aggregate identities, query models, and domain-modeling annotations.

## When to use it

- Declare commands, events, value objects, routes, and bounded contexts in an API or domain-contract module.
- Share `CommandMessage`, `DomainEvent`, `AggregateId`, `Header`, or query DTOs across modules.

Use `wow-core` when you need command dispatch, event sourcing, Saga, or projection runtime behavior. Spring Boot applications normally select capabilities through `wow-spring-boot-starter`.

## Dependency

Maven coordinate: `me.ahoo.wow:wow-api`. Use the Wow BOM to align versions:

```kotlin
dependencies {
    implementation(platform("me.ahoo.wow:wow-bom:<aligned-version>"))
    implementation("me.ahoo.wow:wow-api")
}
```

## Public boundary

The public API lives under `me.ahoo.wow.api.*`, primarily:

- `annotation` for aggregate, command, event, Saga, projection, and route annotations;
- `command`, `event`, `messaging`, and `modeling` for cross-module messages and domain identities;
- `query` for query requests, filters, sorting, pagination, and aggregation models.

This module does not provide dispatchers, EventStore implementations, broker/database adapters, HTTP routes, or Spring auto-configuration. Adding it does not install runtime infrastructure.

## Minimal example

This command and event are reduced from the repository's [cart API](../example/example-api/src/main/kotlin/me/ahoo/wow/example/api/cart/AddCartItem.kt):

```kotlin
import me.ahoo.wow.api.annotation.AllowCreate

@AllowCreate
data class AddCartItem(
    val productId: String,
    val quantity: Int = 1,
)

data class CartItem(
    val productId: String,
    val quantity: Int = 1,
)

data class CartItemAdded(
    val added: CartItem,
)
```

`@AllowCreate` lets the command create its target aggregate when it does not exist. Handling that command belongs to the domain implementation, not `wow-api`.

## Verify

```bash
./gradlew :wow-api:check
```

## Guides

- [Aggregate Modeling](../documentation/docs/en/guide/modeling.md)
- [Command Gateway](../documentation/docs/en/guide/command-gateway.md)
- [Module Dependencies](../documentation/docs/en/guide/advanced/module-dependencies.md)

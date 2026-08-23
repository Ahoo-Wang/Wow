---
title: API Client
description: RESTful API client for Wow based on CoApi, providing reactive and synchronous command sending and snapshot query interfaces.
---

# API Client

The API Client module provides a declarative RESTful client for Wow services based on [CoApi](https://github.com/Ahoo-Wang/CoApi). It offers both reactive and synchronous interfaces for sending commands and querying snapshots.

## Features

- **Reactive and Synchronous APIs** — Choose between `Mono`-based reactive or blocking synchronous interfaces
- **Service Discovery** — Built-in support via `@CoApi` and `@LoadBalanced` annotations
- **Command Gateway** — Send commands with wait plans through REST endpoints
- **Snapshot Query** — Single, list, paged, and count query interfaces

## Installation

Add the `wow-apiclient` dependency and the CoApi Spring Boot starter (required for auto-registration):

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-apiclient")
implementation("me.ahoo.coapi:coapi-spring-boot-starter")
```

You must also enable CoApi client scanning on your application class:

```kotlin
@EnableCoApi(clients = [OrderCommandClient::class, CartQueryClient::class])
@SpringBootApplication
class ExampleServer
```

## Getting Started

### 1. Declare a Query Client

Create a `@CoApi` interface that extends `ReactiveSnapshotQueryApi<S>` (or
`SynchronousSnapshotQueryApi<S>` for blocking calls). The `@HttpExchange` annotation
binds the client to a specific aggregate's snapshot endpoint:

```kotlin
import me.ahoo.coapi.api.CoApi
import me.ahoo.wow.apiclient.query.ReactiveSnapshotQueryApi
import me.ahoo.wow.example.api.cart.CartData
import org.springframework.web.service.annotation.HttpExchange

@CoApi(baseUrl = "http://order-service:8080")
@HttpExchange("cart") // aggregate name = the snapshot endpoint base path
interface CartQueryClient : ReactiveSnapshotQueryApi<CartData>
```

You can override individual methods to customize `@RequestBody` annotations, or simply
inherit all default implementations (single, list, paged, count, and their state/dynamic variants).

### 2. Declare a Command Client

Command clients extend `ReactiveRestCommandGateway` or `SyncRestCommandGateway` directly:

```kotlin
@CoApi(baseUrl = "http://order-service:8080")
interface OrderCommandClient : ReactiveRestCommandGateway
```

### 3. Inject and Use

CoApi auto-configures the client as a Spring bean — inject it directly:

```kotlin
@Service
class CartService(
    private val queryClient: CartQueryClient,
    private val commandClient: OrderCommandClient,
) {
    fun getCart(cartId: String): Mono<CartData> {
        return queryClient.getStateById(cartId) // Mono<CartData>
    }

    fun placeOrder(orderId: String, items: List<CreateOrder.Item>, address: ShippingAddress): Mono<CommandResult> {
        val request = CommandRequest(
            body = CreateOrder(items = items, address = address, fromCart = false),
            aggregateId = orderId,
            waitPlan = CommandRequest.WaitPlan(waitStage = CommandStage.PROCESSED),
        )
        return commandClient.send(request) // Mono<CommandResult>
    }
}
```

### Service Discovery

`ReactiveRestCommandGateway` and `SyncRestCommandGateway` are annotated with `@LoadBalanced`,
so you can use a service-registry URL instead of a fixed host:

```kotlin
@CoApi(baseUrl = "http://order-service") // resolved by Spring Cloud LoadBalancer / Nacos / etc.
interface OrderCommandClient : ReactiveRestCommandGateway
```

::: tip CommandRequest serviceUri
For `send(CommandRequest)`, the command gateway constructs the send URI from
`CommandRequest.serviceUri` or the command metadata's context name — it does **not** use the
`@CoApi(baseUrl)` for command sends. To target a fixed host for commands, set
`CommandRequest(serviceUri = "http://localhost:8080", ...)`.
:::

## Command Gateway

`ReactiveRestCommandGateway` and `SyncRestCommandGateway` are concrete `@CoApi`
interfaces (no extra type parameters). Declare your own `@CoApi` interface that
extends one of them to inherit the `send(CommandRequest)` method.

### Reactive Command Gateway

```kotlin
@CoApi
interface OrderCommandGateway : ReactiveRestCommandGateway
```

`send(request)` returns `Mono<CommandResult>`:

```kotlin
val request = CommandRequest(
    body = CreateOrder(orderId = "order-001", items = listOf(...)),
    waitPlan = CommandRequest.WaitPlan(
        waitStage = CommandStage.PROJECTED,
        waitContext = "order",
        waitProcessor = "OrderProjector",
    ),
)
val result: CommandResult = orderCommandGateway.send(request).block()
```

### Synchronous Command Gateway

```kotlin
@CoApi
interface OrderCommandGateway : SyncRestCommandGateway
```

`send(request)` returns `CommandResult` directly (blocking). A
`WebClientResponseException` is unwrapped into a `RestCommandGatewayException`
carrying the `CommandResult` / `ErrorInfo` body.

## Snapshot Query

### Reactive Query API

```kotlin
@CoApi
interface OrderQueryApi :
    ReactiveSnapshotQueryApi<OrderState>,
    ReactiveSnapshotAggregationQueryApi
```

`ReactiveSnapshotQueryApi<S>` composes single, list, paged, and count operations,
all returning `Mono`/`Flux`:

```kotlin
// Single: returns Mono<MaterializedSnapshot<OrderState>> (empty if not found)
val snapshot = queryApi.getById("order-001").block()
// Use getStateById to obtain the state directly: Mono<OrderState>
val state = queryApi.getStateById("order-001").block()

// Paged: takes an IPagedQuery (1-indexed Pagination); returns Mono<PagedList<...>>
val paged = queryApi.paged(
    PagedQuery(
        condition = Condition.all(),
        pagination = Pagination(index = 1, size = 10),
    ),
).block()

// Count: takes a Condition; returns Mono<Long>
val total = queryApi.count(Condition.all()).block()

// Snapshot aggregation: Flux<Map<String, Any?>>
val rows = queryApi.aggregate(
    AggregationQuery(
        condition = Condition.eq("state.status", "CREATED"),
        metrics = listOf(AggregationMetric.Count("count")),
    ),
).collectList().block()
```

Aggregation is exposed through the separate `ReactiveSnapshotAggregationQueryApi`. The synchronous
`SynchronousSnapshotAggregationQueryApi` returns `List<Map<String, Any?>>`. Existing composite Snapshot
client interfaces stay unchanged so custom implementations remain compatible.
For complete Elements, JSON discriminator, result, and ordering semantics, see
[Snapshot Elements Aggregation](../query.md#snapshot-elements-aggregation).

### Synchronous Query API

```kotlin
@CoApi
interface OrderQueryApi :
    SynchronousSnapshotQueryApi<OrderState>,
    SynchronousSnapshotAggregationQueryApi
```

The synchronous variant mirrors the reactive API but returns values directly
(blocking).

## Error Handling

`RestCommandGatewayException` wraps command errors with full request context:

```kotlin
try {
    orderCommandGateway.send(request).block()
} catch (ex: RestCommandGatewayException) {
    println("Command failed: ${ex.message}")
}
```

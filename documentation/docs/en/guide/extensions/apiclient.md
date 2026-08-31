---
title: API Client
description: Call Wow command and snapshot HTTP contracts through CoApi while preserving route, wait-stage, query-schema, and server-guard boundaries.
---

# API Client

`wow-apiclient` provides hand-maintained CoApi interfaces for Wow's generic command facade and snapshot query routes. It is a transport adapter:

```text
CommandRequest / Query DTO
  -> CoApi HTTP exchange
  -> generated WebFlux route
  -> command or guarded query pipeline
```

It does not generate server routes, discover aggregate-specific fields, create authorization headers, or turn a projection into a server-side `QueryGateway`. The running server's OpenAPI document remains the source of truth for the path and wire contract.

For command-client registration, `CommandRequest`, target-service resolution, reactive/synchronous invocation, wait capabilities, and error mapping, use the authoritative [Command API Client](../command/api-client.md). This page retains extension installation and snapshot-query contracts.

## Features

- Reactive command and query APIs using `Mono` and `Flux`.
- Blocking synchronous variants for callers that deliberately use synchronous I/O.
- Generic command sending through `/wow/command/send` with wait-plan headers.
- Typed, state-only, and dynamic snapshot query response shapes.
- Single, list, paged, exact count, and separately opted-in aggregation calls.
- CoApi load-balanced command gateway support.

`wow-apiclient` is not an OpenAPI code generator. Fetcher or another downstream tool may generate a separate client from `/v3/api-docs`; review that generated diff independently.

## Installation

Add the Wow client and CoApi Spring Boot starter:

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-apiclient")
implementation("me.ahoo.coapi:coapi-spring-boot-starter")
```

Register the exact interfaces CoApi should materialize:

```kotlin
@EnableCoApi(
    clients = [
        CartQueryClient::class,
    ],
)
@SpringBootApplication
class ClientApplication
```

## Getting Started

### 1. Declare a Query Client

Bind a query interface to the aggregate route base. This example targets the unscoped `/cart/...` routes:

```kotlin
@CoApi(baseUrl = "http://order-service:8080")
@HttpExchange("cart")
interface CartQueryClient : ReactiveSnapshotQueryApi<CartData>
```

`ReactiveSnapshotQueryApi<S>` composes single, list, paged, and count interfaces. Their inherited `@PostExchange` paths are relative to `@HttpExchange`: `snapshot/single`, `snapshot/list`, `snapshot/paged`, and `snapshot/count`, plus state-only variants.

When CoApi or application conventions require concrete generic metadata, redeclare methods with the concrete return type and `@RequestBody`, as the repository example clients do. Do not duplicate the route path on every method.

`@HttpExchange("cart")` calls the base, unscoped snapshot-query variant. To call a tenant- or owner-scoped variant, bind an application-owned interface or routing layer to that generated path and supply the required values. Protect the base route explicitly; choosing the scoped client path is not authorization. Do not guess a context-prefixed URL—inspect the server OpenAPI.

### 2. Declare a Command Client

See the [Command API Client](../command/api-client.md) for command-client registration and invocation.

### 3. Inject and Use

```kotlin
@Service
class CartApplicationService(
    private val carts: CartQueryClient,
) {
    fun getCart(id: String): Mono<CartData> = carts.getStateById(id)
}
```

`getStateById` turns HTTP 404 into an empty `Mono`; other query errors propagate.

### Service Discovery

Query clients are different: their `@CoApi(baseUrl)` and `@HttpExchange` base determine the target. Service discovery and route scoping are application configuration, not inferred from the query DTO. Command target resolution belongs to the [Command API Client](../command/api-client.md#target-service-resolution).

## Snapshot Query

See [Query API Client](../query/query-api-client.md) for snapshot data queries, state-only/dynamic results, 404 semantics, and the separate aggregation API.

## Error Handling

Query clients only normalize single-query 404 as empty/null; validation, authorization, rate-limit, timeout, and backend errors remain transport errors for the application to handle. For queries, retry only errors the application's policy classifies as transient; a query-schema validation or HTTP guard rejection will not become valid by repetition. See [Command API Client](../command/api-client.md#error-mapping) and [Failures and Idempotency](../command/reliability.md) for command error and retry boundaries.

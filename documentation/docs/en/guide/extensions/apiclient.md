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

It does not generate server routes, discover aggregate-specific fields, create authorization headers, or turn a projection into a query service. The running server's OpenAPI document remains the source of truth for the path and wire contract.

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
        ReactiveRestCommandGateway::class,
        CartQueryClient::class,
    ],
)
@SpringBootApplication
class ClientApplication
```

The client application must also carry the Wow metadata needed to resolve a command's context when `CommandRequest.context` and `serviceUri` are absent.

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

The built-in gateways are already `@CoApi` and `@LoadBalanced`; they can be registered directly. A named application interface is optional:

```kotlin
@CoApi
interface OrderCommandClient : ReactiveRestCommandGateway
```

Both forms call the generic command facade. They do not call the aggregate-specific command route declared in OpenAPI.

### 3. Inject and Use

```kotlin
@Service
class CartApplicationService(
    private val carts: CartQueryClient,
    private val commands: ReactiveRestCommandGateway,
) {
    fun getCart(id: String): Mono<CartData> = carts.getStateById(id)

    fun createOrder(id: String, command: CreateOrder): Mono<CommandResult> =
        commands.send(
            CommandRequest(
                body = command,
                aggregateId = id,
                serviceUri = "http://order-service:8080",
                waitPlan = CommandRequest.WaitPlan(
                    waitStage = CommandStage.PROCESSED,
                ),
            ),
        )
}
```

`getStateById` turns HTTP 404 into an empty `Mono`; other query errors propagate. Command sending validates `CommandValidator` bodies before the exchange.

### Service Discovery

`CommandRequest.sendUri` is computed as:

```text
(serviceUri ?: "http://" + serviceId) + "/wow/command/send"
```

`serviceId` is the explicit `context`, or the command type's context resolved through `MetadataSearcher`. Because `send(CommandRequest)` passes an absolute URI argument, a command gateway's `@CoApi(baseUrl)` does not select the command destination.

Set `serviceUri` for a fixed address. Otherwise, the `@LoadBalanced` gateway uses the context-derived service host and requires the application's load-balancer integration to resolve it.

Query clients are different: their `@CoApi(baseUrl)` and `@HttpExchange` base determine the target. Service discovery and route scoping are application configuration, not inferred from the query DTO.

## Command Gateway

`CommandRequest` carries the command body plus routing/message headers:

- `aggregateId`, `aggregateVersion`, `tenantId`, `ownerId`, `spaceId`;
- `requestId`, `localFirst`, `context`, `aggregate`, and optional wire `type`;
- `serviceUri` for transport destination;
- `WaitPlan` for the server-side command wait contract.

`type` defaults to `body::class.java.name`. `context` affects both command metadata and default service discovery. Do not set a context or aggregate merely to route around missing KSP metadata; the values must describe the actual command contract.

The default wait stage is `PROCESSED`. Other stages are `SENT`, `SNAPSHOT`, `PROJECTED`, `EVENT_HANDLED`, and `SAGA_HANDLED`. `waitContext` and `waitProcessor` narrow function-based stages. `waitTimeout` is sent in milliseconds.

A wait result reports the selected Wow processing signal. `PROJECTED` is meaningful only for projection work registered in Wow and completed inside the handler's returned chain; it does not wait for detached `subscribe()` calls or an unrelated external pipeline.

### Reactive Command Gateway

```kotlin
val result: Mono<CommandResult> = commandGateway.send(
    CommandRequest(
        body = createOrder,
        aggregateId = "order-1",
        waitPlan = CommandRequest.WaitPlan(
            waitStage = CommandStage.PROJECTED,
            waitContext = "order-service",
            waitProcessor = "OrderSummaryProjector",
            waitTimeout = 5_000,
        ),
    ),
)
```

The reactive gateway returns `Mono<CommandResult>` and maps command HTTP errors to `RestCommandGatewayException`.

### Synchronous Command Gateway

```kotlin
@EnableCoApi(clients = [SyncRestCommandGateway::class])
class ClientConfiguration

val result: CommandResult = syncGateway.send(request)
```

The synchronous gateway blocks the calling thread and returns `CommandResult`. Use it only on a blocking application path; do not call it from Reactor event-loop or core Wow reactive processing code.

## Snapshot Query

See [Query API Client](../query/query-api-client.md) for snapshot data queries, state-only/dynamic results, 404 semantics, and the separate aggregation API.

## Error Handling

For command calls, `RestCommandGatewayException` retains the `CommandRequest`, error code, message, and binding errors when the response can be decoded as `CommandResult` or `DefaultErrorInfo`:

```kotlin
commandGateway.send(request)
    .doOnError(RestCommandGatewayException::class.java) { error ->
        log.warn("Command failed: {}", error.errorCode)
    }
```

Blank or unknown error bodies still become `RestCommandGatewayException` with the HTTP exception as cause. Query clients only normalize single-query 404 as empty/null; validation, authorization, rate-limit, timeout, and backend errors remain transport errors for the application to handle.

Do not retry commands blindly at the HTTP layer. Reuse a stable request/command identity and follow the command idempotency contract. For queries, retry only errors the application's policy classifies as transient; a query-schema validation or HTTP guard rejection will not become valid by repetition.

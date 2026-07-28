---
title: CoSec
description: CoSec security framework integration for Wow, handling security context propagation in command and query endpoints.
---

# CoSec

The CoSec extension integrates the [CoSec](https://github.com/Ahoo-Wang/CoSec) security framework with Wow's WebFlux command and query endpoints, handling security context injection and propagation.

## How It Works

CoSec integration provides four key components:

1. **CommandRequestHeaderAppender** — Extracts `CoSec-App-Id` and `CoSec-Device-Id` from HTTP request headers and appends them to command headers
2. **CommandBuilderExtractor** — Extracts `CoSec-Request-Id` and `CoSec-Space-Id` from HTTP request headers and injects them into the CommandBuilder
3. **MessagePropagator** — Propagates `app_id` and `device_id` from upstream message headers to downstream messages in the processing chain
4. **RewriteRequestCondition** — Resolves the query `spaceId` for snapshot/event-stream queries from the `CoSec-Space-Id` header (falling back to the request space), so read-side queries are scoped to the caller's space

## Installation

Add the `wow-cosec` dependency and enable the `cosec-support` capability in your Spring Boot Starter:

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("cosec-support") }
}
```

## Auto-Configuration

When both `wow-cosec` and CoSec are on the classpath, the `CoSecAutoConfiguration` automatically registers the security integration beans. No additional configuration is required.

## Usage

The integration is transparent: clients send the CoSec headers on each command HTTP request, and the framework propagates them through the command pipeline so that downstream sagas, projections, and event handlers can observe the caller's app, device, request, and space context.

### Sending CoSec Headers

```http
POST /order/owner/{ownerId}/create_order
Content-Type: application/json
Command-Wait-Stage: PROCESSED
CoSec-App-Id: wow-shop
CoSec-Device-Id: 7f6e5d4c-3b2a-1f0e-9d8c-7b6a5f4e3d2c
CoSec-Request-Id: 550e8400-e29b-41d4-a716-446655440000
CoSec-Space-Id: production

{
  "items": [...]
}
```

### How the Context Flows

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant WebFlux as WebFlux Route
    participant Appender as CoSecCommandRequestHeaderAppender
    participant Extractor as CoSecCommandBuilderExtractor
    participant Gateway as CommandGateway
    participant Propagator as CoSecMessagePropagator
    participant Handler as Downstream Handler (Saga/Projection)

    Client->>WebFlux: POST + CoSec-* headers
    WebFlux->>Appender: append(request, header)
    Note over Appender: CoSec-App-Id → header.app_id<br>CoSec-Device-Id → header.device_id
    WebFlux->>Extractor: extract(metadata, body, request)
    Note over Extractor: CoSec-Request-Id → commandBuilder.requestId<br>CoSec-Space-Id → commandBuilder.spaceId
    Extractor-->>WebFlux: CommandBuilder
    WebFlux->>Gateway: send(CommandMessage)
    Gateway->>Propagator: propagate(header, upstream)
    Note over Propagator: Copy app_id/device_id<br>to every downstream command/event
    Propagator-->>Handler: Message with app_id/device_id
```

| Header | Extracted by | Injected into |
|---|---|---|
| `CoSec-App-Id` | `CoSecCommandRequestHeaderAppender` | command `header.app_id`, propagated to downstream messages |
| `CoSec-Device-Id` | `CoSecCommandRequestHeaderAppender` | command `header.device_id`, propagated to downstream messages |
| `CoSec-Request-Id` | `CoSecCommandBuilderExtractor` | `CommandBuilder.requestId` (idempotency) |
| `CoSec-Space-Id` | `CoSecCommandBuilderExtractor` + `CoSecRewriteRequestCondition` | `CommandBuilder.spaceId` and the read-side query `spaceId` scope |

To access the propagated context inside a handler, read it from the message header:

```kotlin
@StatelessSaga
class OrderSaga {
    fun onEvent(event: OrderCreated, exchange: DomainEventExchange<*>): Mono<Void> {
        val appId = exchange.message.header["app_id"]
        val deviceId = exchange.message.header["device_id"]
        // ... use the caller's app/device context
        return Mono.empty()
    }
}
```

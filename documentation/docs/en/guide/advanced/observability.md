---
title: Observability
description: End-to-end observability integration for the Wow framework.
---

# Observability

![Observability](../../../public/images/observability/observability.png)

Wow provides end-to-end observability through two complementary integrations:

- **Metrics** (Micrometer/Reactor) — counters and timers for command, event, event-store, snapshot, projection, saga, and dispatcher operations. See [Metrics](./metrics) for the full metric catalogue and the `wow.metrics.enabled` switch.
- **Distributed tracing** (OpenTelemetry) — `wow-opentelemetry` instruments every cross-cutting component with OpenTelemetry spans. See [OpenTelemetry](../extensions/opentelemetry) for the instrumenter list and attribute tags.

## What the OpenTelemetry module instruments

When `wow-opentelemetry` is on the classpath, the `WowOpenTelemetryAutoConfiguration` registers tracing decorators for:

| Category | Instrumented components |
|---|---|
| **Command path** | `CommandGateway` wait plan, `CommandBus` producer |
| **Event path** | `DomainEventBus` / `StateEventBus` producers, event processor, projection, stateless saga |
| **Persistence** | `EventStore`, `SnapshotStore`, snapshot repository |
| **Aggregate** | Aggregate processing filter chain |

Initialize `GlobalOpenTelemetry` (via the OpenTelemetry Java agent or an SDK registered during bootstrap) **before** the Wow application context creates the tracing filters and decorators. Registering the SDK after the Wow tracing instrumenters have initialized is too late.

## Correlating Your Own Spans

Wow propagates the OpenTelemetry `Context` through the Reactor pipeline (stored in the Reactor
context). Any child span you create inside a command handler, saga, or projection automatically
links to the Wow command's trace — no manual context passing required.

### In a Command Handler

```kotlin
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer

@AggregateRoot
class Order(private val state: OrderState) {
    private val tracer: Tracer = GlobalOpenTelemetry.getTracer("order-domain")

    @OnCommand
    fun onCommand(command: CreateOrder, exchange: ServerCommandExchange<*>): OrderCreated {
        val span = tracer.spanBuilder("validate-inventory")
            .setAttribute("order.item_count", command.items.size)
            .startSpan()
        try {
            // Your business logic — this span appears as a child of
            // the Wow-generated "order.create_order" aggregate span
            validateItems(command.items)
            return OrderCreated(...)
        } finally {
            span.end()
        }
    }
}
```

### In a Reactive Handler (Mono/Flux)

For reactive handlers, the OTel context is carried in the Reactor context. Use
`ContextView`-aware span creation so the parent trace is preserved across async boundaries:

```kotlin
@ProjectionProcessor
class OrderProjection {
    private val tracer = GlobalOpenTelemetry.getTracer("order-projection")

    @OnEvent
    fun onOrderCreated(event: OrderCreated): Mono<Void> {
        return Mono.deferContextual { ctx ->
            // The Wow TraceFilter already stored the OTel Context in the Reactor context.
            // GlobalOpenTelemetry picks it up automatically when you create a span here.
            val span = tracer.spanBuilder("project-order-summary")
                .setAttribute("order.id", event.orderId)
                .startSpan()
            orderSummaryRepository
                .save(buildSummary(event))
                .doFinally { span.end() }
                .then()
        }
    }
}
```

The resulting trace in Jaeger/Zipkin/Tempo shows your business span nested inside the Wow
framework span, giving you end-to-end visibility from HTTP request through domain logic to
read-model update.

## Installation

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-opentelemetry")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-opentelemetry'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-opentelemetry</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::

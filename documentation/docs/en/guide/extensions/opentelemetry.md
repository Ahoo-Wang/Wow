---
title: OpenTelemetry
description: Add OpenTelemetry tracing to Wow runtime operations without owning the SDK or exporter.
---

# OpenTelemetry

`wow-opentelemetry` creates tracing instrumenters for command/event sends, aggregates, EventStore, SnapshotStore, projections, Sagas, event processors, and wait plans. Use it to diagnose cross-service latency and failures. It is not required for Micrometer-only metrics.

The module creates spans and propagates context only. It does not initialize an OpenTelemetry SDK, Agent, sampler, exporter, or Collector. `GlobalOpenTelemetry` must be initialized before Wow instrumenters are created.

## Installation

For a Spring Boot application, request the starter capability so auto-configuration is present:

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities {
        requireCapability("me.ahoo.wow:opentelemetry-support")
    }
}
```

Without Spring, depend on the module directly:

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-opentelemetry")
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-opentelemetry</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::

The module on the classpath wires nothing by itself: decorate the supported buses, stores and the command gateway with the `Tracing.tracing()` decorators, and register the filters yourself.

## Quick Start with OTLP

The shortest runtime path is a Java Agent that initializes the global instance before application startup:

```bash
export JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar"
export OTEL_SERVICE_NAME=order-service
export OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
java -jar app.jar
```

The Agent/SDK owns endpoint, protocol, headers, sampling, and resource attributes. A module check or local span proves instrumentation behavior only; Collector/backend receipt proves export.

All instrumenters capture `GlobalOpenTelemetry` when their singleton objects initialize. Initialize the SDK before
the Wow ApplicationContext creates filters, instrumentations or decorators. The OpenTelemetry Java Agent satisfies this ordering
because it starts before application bootstrap.

## Configuration

Wow has one switch, enabled by default:

```yaml
wow:
  opentelemetry:
    enabled: true
```

`false` disables only Wow tracing filters/decorators, not the Java Agent or spans from other libraries. Do not configure duplicate exporters. Micrometer Registry still owns metrics; see [Observability configuration](../../reference/config/observability.md).

## What Wow Instruments

The Spring starter registers a command instrumentation (`TraceCommandInstrumentation`, since 9.3.0; before it the command filter `TraceAggregateFilter`), four event-side processing filters, and decorates supported infrastructure beans:

| Runtime stage | Instrumentation scope | Representative span name |
|---|---|---|
| Command publication | `me.ahoo.wow-commandProducer` | `<aggregate>.<command>.command send` |
| Aggregate execution | `me.ahoo.wow-aggregate` | `<aggregate>.<command>` |
| Event persistence | `me.ahoo.wow-eventStore` | `<aggregate>.<event>.event.append`, `<aggregate>.event.load` |
| Domain/state publication | `me.ahoo.wow-eventProducer`, `me.ahoo.wow-stateEventProducer` | `<aggregate>.<event>.event send`, `<aggregate>.<event>.state_event send` |
| Event processing | `me.ahoo.wow-eventProcessor`, `-projection`, `-statelessSaga` | Event-function qualified name |
| Snapshot processing/storage | `me.ahoo.wow-snapshot`, `-snapshotStore` | `<aggregate>.snapshot`, `.snapshot.save`, `.snapshot.load`, `.snapshot.version` |
| Command wait plan | `me.ahoo.wow-wait` | `<aggregate>.<command>.waiting` |

Instrumenters that register `MessageAttributesExtractor` or `ExchangeAttributesExtractor` add `wow.message.id`,
optional `wow.message.request_id` and `wow.message.trace_id`, plus `wow.aggregate.context_name`,
`wow.aggregate.name`, `wow.aggregate.id`, and `wow.aggregate.tenant_id` when an aggregate identity is present.
Store operations that use `AggregateIdAttributesExtractor` add only the aggregate attributes. `WaitPlanInstrumenter`
registers no attribute extractor, so the `.waiting` span does not automatically receive these Wow message or
aggregate attributes. These are trace attributes, not low-cardinality metric tags.

## How Tracing Is Wired

`WowOpenTelemetryAutoConfiguration` registers filters for aggregates, projections, snapshots, stateless Sagas, and event processors. `TracingBeanPostProcessor` runs at highest precedence and decorates supported local/distributed buses, EventStore, SnapshotStore, and CommandGateway, using `Traced` to prevent double wrapping.

Producer instrumenters inject the OpenTelemetry propagation headers into the Wow message header; consumer filters and
the command instrumentation extract them. `TraceMono` and `TraceFlux` restore the OpenTelemetry `Context` for subscription and asynchronous
signals. The wait decorator preserves the command gateway's
runtime receiver/admission contract.

Reactor publishers start spans on subscription and end them on completion, error, cancellation, or synchronous subscribe failure. Attributes exist only on instrumenters that registered the corresponding extractor, so not every span has every `wow.message.*` or `wow.aggregate.*` field.

Verified failure boundaries: initializing the global SDK too late leaves already-created instrumenters on the old global instance; publisher failures are recorded and propagated; disabling Wow tracing does not change business calls. Focused check:

```bash
./gradlew :wow-opentelemetry:check
```

## Add Your Own Spans

Create business spans only around meaningful remote calls or expensive domain work. Keep aggregate IDs out of metric
tags; they are appropriate trace attributes.

### In a Command Handler

Wow makes its span current while invoking the nested work, so use `Context.current()` explicitly as the parent and
scope the business call:

```kotlin
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context

private val tracer = GlobalOpenTelemetry.getTracer("order-domain")

@OnCommand
fun handle(command: CreateOrder): OrderCreated {
    val span = tracer.spanBuilder("inventory.validate")
        .setParent(Context.current())
        .setAttribute("order.item_count", command.items.size.toLong())
        .startSpan()
    return try {
        span.makeCurrent().use {
            validateItems(command.items)
            OrderCreated(command.id)
        }
    } catch (error: Throwable) {
        span.recordException(error)
        throw error
    } finally {
        span.end()
    }
}
```

This example is synchronous. Do not leave a scope open across an asynchronous boundary or a different thread.

### In a Reactive Handler (Mono/Flux)

Build the span at subscription time, and end it from the complete Reactor lifecycle:

```kotlin
@OnEvent
fun onOrderCreated(event: OrderCreated): Mono<Void> = Mono.defer {
    val span = tracer.spanBuilder("order_summary.save")
        .setParent(Context.current())
        .setAttribute("order.id", event.orderId)
        .startSpan()

    Mono.defer {
        orderSummaryRepository.save(buildSummary(event))
    }.doOnError(span::recordException)
        .doFinally { span.end() }
        .then()
}
```

The inner `Mono.defer` is intentional: synchronous exceptions from `buildSummary` or `save` become error signals
before `doOnError` and `doFinally` are attached. Completion, error, or cancellation therefore ends the span once.

If custom operators escape the instrumented subscriber chain, propagate an OpenTelemetry `Context` deliberately and
cover that boundary with an integration test. The module tests verify restoration across `publishOn`, nested traced
publishers, cancellation, and source errors.

## What a Trace Looks Like

A command commonly forms command send/wait → aggregate handler → event append/send → projection/Saga/snapshot parent-child spans. Actual spans depend on which path ran, sampling, and successful export.

Then read [Observability](../advanced/observability.md) to use traces together with metrics during an incident, and retain a real trace receipt from the target Collector.

<!-- Sources: wow-opentelemetry/src/main/kotlin/me/ahoo/wow/opentelemetry/, its TracePublisherTest and
TracingCommandGatewayWaitTest, and wow-spring-boot-starter/.../opentelemetry/ -->

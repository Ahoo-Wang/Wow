---
title: OpenTelemetry
description: OpenTelemetry integration for vendor-neutral distributed tracing and monitoring.
---

# OpenTelemetry

OpenTelemetry is a vendor-neutral open source project designed to provide standard APIs, tools, and libraries for tracing and monitoring distributed applications.
Supported by the Cloud Native Computing Foundation (CNCF) and the OpenTelemetry community.

Its main goal is to provide developers with consistent tracing solutions to help them collect, generate, and export tracing data for distributed systems to better understand application performance, behavior, and exceptions.
OpenTelemetry supports multiple programming languages and frameworks such as Java, Python, Go, Node.js, making it easy for developers to integrate tracing functionality.

The OpenTelemetry project provides the following core features:
- Distributed Tracing: Captures the passage of requests between different services and components, forming call chains to track the path and execution time of entire distributed requests.
- Metrics Collection: Collects and exports performance metrics such as request rate, response time, error rate, etc., helping developers monitor and optimize performance.
- Logging: Collects application log data, associates it with tracing and metrics data, providing deep insights into application behavior and issues.

The Wow `wow-opentelemetry` module deliberately covers **distributed tracing only**. It provides
instrumenters for the framework's core operations, while Wow metrics remain Micrometer meters and
are exported by a Micrometer registry. The module does not initialize an OpenTelemetry SDK or
exporter by itself.

- `AggregateInstrumenter`: Aggregate root instrumenter, used to record aggregate root operations.
- `EventProcessorInstrumenter`: Event processor instrumenter, used to record event processor operations.
- `EventStoreInstrumenter`: Event store instrumenter, used to record event store operations.
- `CommandProducerInstrumenter`: Command producer instrumenter, used to record command producer operations.
- `EventProducerInstrumenter`: Event producer instrumenter, used to record event producer operations.
- `StateEventProducerInstrumenter`: State event producer instrumenter, used to record state event producer operations.
- `ProjectionInstrumenter`: Projection instrumenter, used to record projection operations.
- `StatelessSagaInstrumenter`: Stateless Saga instrumenter, used to record stateless Saga operations.
- `SnapshotInstrumenter`: Snapshot instrumenter, used to record snapshot operations.
- `SnapshotStoreInstrumenter`: Snapshot store instrumenter, used to record snapshot store operations.
- `WaitPlanInstrumenter`: Command wait-plan instrumenter, used to record command wait/notify operations.

Supports the following attribute tags:

- `wow.aggregate.context_name`: Aggregate root context name.
- `wow.aggregate.tenant_id`: Aggregate root tenant ID.
- `wow.aggregate.name`: Aggregate root name.
- `wow.aggregate.id`: Aggregate root ID.
- `wow.message.id`: Message ID
- `wow.message.request_id`: Command message request ID.
- `wow.message.trace_id`: Trace ID propagated through the message header.

![Observability](../../../public/images/observability/observability.png)

## Installation

For a Gradle-based Spring Boot application, request the starter's `opentelemetry-support` capability.
This is the recommended single dependency entry point: it brings in `wow-opentelemetry` and enables
the corresponding Wow auto-configuration.

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities {
        requireCapability("me.ahoo.wow:opentelemetry-support")
    }
}
```
```groovy [Gradle(Groovy)]
implementation('me.ahoo.wow:wow-spring-boot-starter') {
    capabilities {
        requireCapability('me.ahoo.wow:opentelemetry-support')
    }
}
```
```xml [Maven]
<!-- Maven does not resolve Gradle feature capabilities. Keep wow-spring-boot-starter
     in the application and add the tracing module explicitly. -->
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-opentelemetry</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::

When not using Spring Boot auto-configuration, depend on `wow-opentelemetry` directly and register
the tracing filters/decorators yourself.

## Quick Start with OTLP

The recommended runtime is the OpenTelemetry Java Agent. It initializes `GlobalOpenTelemetry`
before Wow creates its tracing instrumenters:

```bash
export JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar"
export OTEL_SERVICE_NAME=order-service
export OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
java -jar your-app.jar
```

The general endpoint uses OTLP/HTTP: the Agent appends `/v1/traces`. If the application also has
Spring Boot Actuator, `spring-boot-opentelemetry`, and `micrometer-registry-otlp`, Micrometer reuses
the same service name and endpoint and appends `/v1/metrics`. No Wow-specific exporter
configuration is required.

Do not enable the Java Agent's Micrometer bridge when `micrometer-registry-otlp` is present; both
paths would export the same application meters. See
[Observability Configuration](/reference/config/observability) for dependencies, authentication,
verification, and signal-specific endpoint overrides.

## Configuration

When the starter auto-configuration is active and `wow-opentelemetry` is on the classpath, Wow
tracing is enabled by default. It can be disabled without removing the dependency:

```yaml
wow:
  opentelemetry:
    enabled: false
```

If you use an SDK instead of the Agent, initialize `GlobalOpenTelemetry` before the Wow application
context creates tracing filters and decorators. Registering the SDK after Wow tracing instrumenters
have initialized is too late.

## How Tracing Is Wired

`WowOpenTelemetryAutoConfiguration` registers five `ExchangeFilter` beans (each `@ConditionalOnMissingBean`, so you can override any one) plus a `TracingBeanPostProcessor` that decorates the command/event/state-event buses, the event store, and the snapshot store:

| Bean | Filter stage | Span name pattern |
|---|---|---|
| `TraceAggregateFilter` | Aggregate command processing | `{aggregateName}.{commandName}` |
| `TraceProjectionFilter` | Projection event handling | `{processorName}.{functionName}({eventType})` (via `EventProcessorSpanNameExtractor`) |
| `TraceStatelessSagaFilter` | Saga event handling | `{processorName}.{functionName}({eventType})` (same extractor) |
| `TraceSnapshotFilter` | Snapshot creation | `{aggregateName}.snapshot` |
| `TraceEventProcessorFilter` | General event-processor handling | `{processorName}.{functionName}({eventType})` (same extractor) |

Every span carries the `wow.aggregate.*` and `wow.message.*` attributes listed above, plus the OpenTelemetry propagation context, so a single command's full path (command bus → aggregate → event store → projection → saga) appears as one distributed trace.

## What a Trace Looks Like

For a `CreateOrder` command on the `order` aggregate that triggers a saga and a projection, the trace hierarchy is:

```text
order.create_order                    (TraceAggregateFilter — aggregate command)
├── order.OrderCreated.event.append   (TracingEventStore — event persistence)
├── order.OrderCreated.event send     (TracingEventBus — message publish)
│   ├── OrderSaga.onEvent(OrderCreated) (TraceStatelessSagaFilter — saga handler)
│   └── OrderProjection.onEvent(OrderCreated) (TraceProjectionFilter — projection handler)
└── order.snapshot                    (TraceSnapshotFilter — snapshot creation)
```

Span names for projection/saga/event-processor use the function's `qualifiedName` format
(`{processorName}.{functionName}({eventType})`). Producer instrumenters inject their context
into the message, so consumer spans are **children** of the producer span — not siblings.
The key point is that all spans for one command share the same trace ID via context propagation.

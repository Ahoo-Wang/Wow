---
title: OpenTelemetry
description: OpenTelemetry integration for vendor-neutral distributed tracing and monitoring.
---

# OpenTelemetry

OpenTelemetry is a vendor-neutral open source project designed to provide standard APIs, tools, and libraries for tracing and monitoring distributed applications.
Supported by the Cloud Native Computing Foundation (CNCF) and the OpenTelemetry community.

Its main goal is to provide developers with consistent tracing solutions to help them collect, generate, and export tracing data for distributed systems to better understand application performance, behavior, and exceptions.
OpenTelemetry supports multiple programming languages and frameworks such as Java, Python, Go, Node.js, making it easy for developers to integrate tracing functionality.

OpenTelemetry provides the following core features:
- Distributed Tracing: Captures the passage of requests between different services and components, forming call chains to track the path and execution time of entire distributed requests.
- Metrics Collection: Collects and exports performance metrics such as request rate, response time, error rate, etc., helping developers monitor and optimize performance.
- Logging: Collects application log data, associates it with tracing and metrics data, providing deep insights into application behavior and issues.

The _OpenTelemetry_ module of the Wow framework provides a series of instrumenters to record operations of the framework's core components, helping developers better understand application performance, behavior, and exceptions.

- `AggregateInstrumenter`: Aggregate root instrumenter, used to record aggregate root operations.
- `EventProcessorInstrumenter`: Event processor instrumenter, used to record event processor operations.
- `EventStoreInstrumenter`: Event store instrumenter, used to record event store operations.
- `CommandProducerInstrumenter`: Command producer instrumenter, used to record command producer operations.
- `EventProducerInstrumenter`: Event producer instrumenter, used to record event producer operations.
- `StateEventProducerInstrumenter`: State event producer instrumenter, used to record state event producer operations.
- `ProjectionInstrumenter`: Projection instrumenter, used to record projection operations.
- `StatelessSagaInstrumenter`: Stateless Saga instrumenter, used to record stateless Saga operations.
- `SnapshotInstrumenter`: Snapshot instrumenter, used to record snapshot operations.
- `SnapshotRepositoryInstrumenter`: Snapshot repository instrumenter, used to record snapshot repository operations.
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

## Configuration

When `wow-opentelemetry` is on the classpath, Wow tracing auto-configuration is enabled by default. It can be disabled without removing the dependency:

```yaml
wow:
  opentelemetry:
    enabled: false
```

Initialize `GlobalOpenTelemetry` before the Wow application context creates tracing filters and decorators. Use the OpenTelemetry Java agent or register an SDK during application bootstrap; registering the SDK after Wow tracing instrumenters have initialized is too late.

## How Tracing Is Wired

`WowOpenTelemetryAutoConfiguration` registers five `ExchangeFilter` beans (each `@ConditionalOnMissingBean`, so you can override any one) plus a `TracingBeanPostProcessor` that decorates the command/event/state-event buses, the event store, and the snapshot store:

| Bean | Filter stage | Span name pattern |
|---|---|---|
| `TraceAggregateFilter` | Aggregate command processing | `{aggregateName}.{commandName}` |
| `TraceProjectionFilter` | Projection event handling | `{aggregateName}.projection.{eventName}` |
| `TraceStatelessSagaFilter` | Saga event handling | `{aggregateName}.saga.{eventName}` |
| `TraceSnapshotFilter` | Snapshot creation | `{aggregateName}.snapshot` |
| `TraceEventProcessorFilter` | General event-processor handling | `{aggregateName}.event-processor.{eventName}` |

Every span carries the `wow.aggregate.*` and `wow.message.*` attributes listed above, plus the OpenTelemetry propagation context, so a single command's full path (command bus → aggregate → event store → projection → saga) appears as one distributed trace.

## What a Trace Looks Like

For a `CreateOrder` command on the `order` aggregate that triggers a saga and a projection, the trace hierarchy is:

```text
order.create_order              (TraceAggregateFilter — aggregate command)
├── wow-mongo append            (TracingEventStore — event persistence)
├── order.event-store.send      (TracingCommandBus/EventBus — message publish)
├── order.snapshot.save         (TraceSnapshotFilter — snapshot creation)
├── order.saga.OrderCreated     (TraceStatelessSagaFilter — saga onOrderCreated)
└── order.projection.OrderCreated (TraceProjectionFilter — projection onOrderCreated)
```

The exact span names depend on the instrumenter's `SpanNameExtractor`; the key point is that all spans for one command share the same trace ID via context propagation, giving you end-to-end visibility from HTTP request to read-model update.

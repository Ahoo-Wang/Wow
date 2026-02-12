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

Supports the following attribute tags:

- `wow.aggregate.context_name`: Aggregate root context name.
- `wow.aggregate.tenant_id`: Aggregate root tenant ID.
- `wow.aggregate.name`: Aggregate root name.
- `wow.aggregate.id`: Aggregate root ID.
- `wow.message.id`: Message ID
- `wow.message.request_id`: Command message request ID.

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
---
title: OpenTelemetry
description: Add OpenTelemetry tracing to Wow runtime operations without owning the SDK or exporter.
---

# OpenTelemetry

`wow-opentelemetry` creates tracing instrumenters for command/event sends, aggregates, EventStore, SnapshotStore, projections, Sagas, event processors, and wait plans. Use it to diagnose cross-service latency and failures. It is not required for Micrometer-only metrics.

The module creates spans and propagates context only. It does not initialize an OpenTelemetry SDK, Agent, sampler, exporter, or Collector. `GlobalOpenTelemetry` must be initialized before Wow instrumenters are created.

## Installation

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:opentelemetry-support") }
}
```

Non-Spring applications may depend directly on `me.ahoo.wow:wow-opentelemetry`, but must decorate buses/stores/gateways and register filters themselves. Classpath presence alone does not wire them.

## Quick Start with OTLP

The shortest runtime path is a Java Agent that initializes the global instance before application startup:

```bash
export JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar"
export OTEL_SERVICE_NAME=order-service
export OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
java -jar app.jar
```

The Agent/SDK owns endpoint, protocol, headers, sampling, and resource attributes. A module check or local span proves instrumentation behavior only; Collector/backend receipt proves export.

## Configuration

Wow has one switch, enabled by default:

```yaml
wow:
  opentelemetry:
    enabled: true
```

`false` disables only Wow tracing filters/decorators, not the Java Agent or spans from other libraries. Do not configure duplicate exporters. Micrometer Registry still owns metrics; see [Observability configuration](../../reference/config/observability.md).

## How Tracing Is Wired

`WowOpenTelemetryAutoConfiguration` registers filters for aggregates, projections, snapshots, stateless Sagas, and event processors. `TracingBeanPostProcessor` runs at highest precedence and decorates supported local/distributed buses, EventStore, SnapshotStore, and CommandGateway, using `Traced` to prevent double wrapping.

Reactor publishers start spans on subscription and end them on completion, error, cancellation, or synchronous subscribe failure. Producers inject message context; consumers/exchanges restore it. Attributes exist only on instrumenters that registered the corresponding extractor, so not every span has every `wow.message.*` or `wow.aggregate.*` field.

Verified failure boundaries: initializing the global SDK too late leaves already-created instrumenters on the old global instance; publisher failures are recorded and propagated; disabling Wow tracing does not change business calls. Focused check:

```bash
./gradlew :wow-opentelemetry:check
```

## What a Trace Looks Like

A command commonly forms command send/wait → aggregate handler → event append/send → projection/Saga/snapshot parent-child spans. Actual spans depend on which path ran, sampling, and successful export.

Next, read [Observability](../advanced/observability.md) for span and attribute contracts, then retain a real trace receipt from the target Collector.

---
title: Observability Configuration
description: Configuration options for OpenAPI spec generation, OpenTelemetry tracing, and metrics export.
---

# Observability Configuration

## OpenAPI

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.openapi.enabled` | Boolean | `true` | Enable OpenAPI spec generation |

```yaml
wow:
  openapi:
    enabled: true
```

When enabled, Wow builds the OpenAPI specification at **runtime** from the command and event
models registered in the bounded context (`RouterSpecs` bean built in `OpenAPIAutoConfiguration`).
The `wow-compiler` module contributes command routing metadata at compile time, but the spec
itself — including routes, schemas, and the bundled Swagger UI — is assembled when the
application context starts.

## OpenTelemetry

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.opentelemetry.enabled` | Boolean | `true` | Enable OpenTelemetry tracing instrumentation of the command/event pipeline |

```yaml
wow:
  opentelemetry:
    enabled: true
```

Enabled by default (`matchIfMissing = true`) when the `wow-opentelemetry` module and the
`WowInstrumenter` class are on the classpath. Set to `false` to disable distributed tracing
spans across the command bus, event store, projections, and sagas.

## Metrics

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.metrics.enabled` | Boolean | `true` | Enable Micrometer/Prometheus metrics collection |

```yaml
wow:
  metrics:
    enabled: true
```

Enabled by default (`matchIfMissing = true`). Governs the export of framework metrics
(command processing latency, event store append counts, projection lag, etc.). Disable to
suppress Wow-specific meters.

## Business Intelligence Scripts

The `wow.bi.script.*` property tree (ClickHouse/BI script deployment) is documented on the
[BI Operations](/guide/bi-operations) page.

## Integration Setup

### Enabling Metrics Export (Prometheus)

Wow metrics are written to Micrometer's global registry. To expose them via Prometheus, add the
Spring Boot Actuator + Prometheus registry dependencies and expose the endpoint:

```yaml
management:
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include:
          - health
          - prometheus    # Micrometer/Prometheus scrape endpoint
          - threaddump
  metrics:
    tags:
      application: ${spring.application.name}   # common tag on all meters

springdoc:
  show-actuator: true   # include actuator endpoints in OpenAPI
```

```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
implementation("io.micrometer:micrometer-registry-prometheus")
```

Scrape the `/actuator/prometheus` endpoint from Prometheus. The Wow-specific meters
(`wow.command.*`, `wow.eventstore.*`, `wow.snapshot.*`, `wow.projection.*`, etc.) appear
alongside standard JVM/Reactor meters. See [Metrics](/guide/advanced/metrics) for the full
catalogue.

### Enabling Distributed Tracing (OpenTelemetry)

The recommended way to enable tracing is the OpenTelemetry Java Agent, which bootstraps
`GlobalOpenTelemetry` before the Spring context starts:

```bash
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.service.name=${spring.application.name} \
     -Dotel.exporter.otlp.endpoint=http://otel-collector:4317 \
     -jar your-app.jar
```

Add `wow-opentelemetry` to your dependencies. The auto-configuration detects the agent's
initialized `GlobalOpenTelemetry` and registers the Wow tracing filters and decorators
automatically. Set `wow.opentelemetry.enabled=false` only to disable Wow's spans while
keeping the agent's other instrumentation.

```kotlin
implementation("me.ahoo.wow:wow-opentelemetry")
```

See [Observability](/guide/advanced/observability) for the instrumentation coverage and
[OpenTelemetry Extension](/guide/extensions/opentelemetry) for the instrumenter list.

---
title: Observability
description: Trace and metric evidence across the Wow command, event, storage, and recovery pipeline.
---

# Observability

![Observability](/images/observability/observability.png)

Wow exposes two different views of the same runtime pipeline:

- [Metrics](./metrics) aggregate finite operations, receiver streams, and batch activity with bounded Micrometer
  tags.
- `wow-opentelemetry` creates OpenTelemetry spans and propagates message trace context across command, event, state,
  persistence, and wait boundaries.

Use metrics to locate a failing or slow stage, then use traces to follow one execution. Neither signal replaces
backend reconciliation, projection-lag checks, or deployment evidence.

## Where Each Signal Is Documented

| Signal | Page |
| --- | --- |
| Metrics: names, tags, storage batch and query metrics, export | [Metrics](./metrics) |
| Traces: installation, instrumented stages, span names and attributes, your own spans | [OpenTelemetry](../extensions/opentelemetry) |
| Switches and exporter wiring | [Observability Configuration](/reference/config/observability) |

## Investigate an Incident

For one incident, follow this evidence chain:

1. select the metric stage and time window by `component`, `operation`, `context`, `aggregate`, and `outcome`;
2. find a matching trace by service, operation, aggregate, request ID, or message ID;
3. confirm the span reaches the expected store and downstream processor;
4. reconcile the final backend version/read model and deployment revision separately.

<!-- Sources: wow-opentelemetry/src/main/kotlin/me/ahoo/wow/opentelemetry/, its TracePublisherTest and
TracingCommandGatewayWaitTest, and wow-spring-boot-starter/.../opentelemetry/ -->

---
title: Health, Metrics and Tracing
description: The node's health in Actuator's readiness and liveness groups, the metrics and spans wow-opentelemetry records, and what to alert on.
outline: deep
---

# Health, Metrics and Tracing

A node tells the platform whether to send it traffic and whether to restart it, through Spring Boot Actuator's health groups. With `wow-opentelemetry`, it also records a span for each stage of its work, in the trace of the request, entry or message that started it, and metrics of commits, subscriptions, timers and queries. This page describes both and lists what to alert on.

## Health

With Spring Boot Actuator's health support on the class path, the host contributes the node's health:

| Endpoint | Reports |
| --- | --- |
| `/actuator/health/readiness` | Whether the node should receive traffic: Actuator's own readiness and the node's |
| `/actuator/health/liveness` | Whether the node is alive: Actuator's own liveness and the node's |
| `/actuator/health/wow` | Every component of the node, each `UP`, `DEGRADED` or `DOWN` |

The components:

| Component | `DOWN` (not ready) when | `DEGRADED` when |
| --- | --- | --- |
| `node` | The node is not running: starting, draining or stopped | |
| `stores` | A log has kept failing for `wow.health.stores.down-after` (15 s) with no success since | A log failed within `wow.health.stores.window` (1 min) |
| `ownership` | Its lease store is down, or the node owns none of its share of a lease group's partitions past `wow.health.ownership.handover-grace` (1 min) | It owns another set than its share for that long, or the lease store failed lately |
| `topics` | A topic of another context it reads is still joining (unless `wow.health.topics.required: false`) | |
| `subscriptions` | Never | A subscription is `lag-degraded-at` (10,000) entries behind, or has `parked-degraded-at` (100) parked entries |
| `executor` | | The executor runs at `saturation` (90 %) of its in-flight bound |
| `liveness` | The node is stuck: its dispatcher does not tick, or commands in flight do not finish while every log answers, for `wow.health.liveness.stall-timeout` (3 min). This alone fails liveness | |

A degraded component does not change a group's status: it is there to be seen and alerted on. Liveness never fails for a storage outage or while the node drains, so an outage does not restart every node at once. The node samples what a probe must not wait for (the logs' heads, the subscriptions' lag, the parked entries) every `wow.health.sample-interval` (10 s), so a probe answers at once.

What a response shows beyond the status follows Actuator's own settings (`management.endpoint.health.show-details`, `never` by default). The details hold counts, states and exception class names, never messages or addresses. [Kubernetes](./kubernetes#probes) shows the probes.

## Metrics and traces

Put `wow-opentelemetry` on the class path. The spans go through the application's `OpenTelemetry` bean, such as Spring Boot's with an SDK and its exporters, else the global one, such as the Java agent's. The metrics go to the application's Micrometer `MeterRegistry`, such as Actuator's with a Prometheus or OTLP registry, else Micrometer's global registry. `wow.opentelemetry.enabled: false` turns both off.

**Spans.** Each stage of a command and of a subscription is a span, in the trace its request, log entry or Kafka message carries:

| Stage | Span |
| --- | --- |
| Admission | `admit <context>.<aggregate>`, a server span |
| Execution, decision, append | `execute`, `decide`, `append <context>.<aggregate>` |
| A subscription handling an entry of the log | `handle <subscription>` |
| A subscription handling a message of a topic | `<topic> process`, a consumer span |
| A timer firing | `fire <command type>` |
| A query | A span per query |
| A Kafka send | A producer span per send |

The spans carry the context, aggregate, tenant, command and subscription as attributes (`wow.context`, `wow.aggregate`, `wow.tenant.id`, `wow.command`, `wow.subscription`, …), so a trace follows a command through its sagas across services.

**Metrics.** The main ones, tagged by context, aggregate, subscription or partition:

| Metric | Type | Measures |
| --- | --- | --- |
| `wow.command.commit` | Timer | A command's execution, from taking it to its outcome, by outcome |
| `wow.command.refused` | Counter | Commands that ended before the kernel took them: refusals, busy lanes or nodes, unavailable stores |
| `wow.subscription.lag` | Timer | From a commit to a subscription handling it |
| `wow.subscription.backlog` | Gauge | The entries of a partition after a subscription's position |
| `wow.subscription.progress` | Gauge | A subscription's position over the log's head, 0 to 1: a rebuild's progress |
| `wow.subscription.parked` | Gauge | The aggregates a subscription has parked |
| `wow.subscription.paused` | Gauge | 1 while a subscription has paused a partition on a systemic failure |
| `wow.subscription.throttled` | Gauge | 1 while a subscription throttles a partition on backpressure |
| `wow.subscription.undecodable` | Counter | Messages of a topic this version cannot decode |
| `wow.timer.delay` | Timer | How late a timer fires after its due time |
| `wow.query` | Timer | A query, from its admission to its end |
| `wow.query.limit` | Gauge | A store's current bound on concurrent queries |
| `wow.kafka.offset.gaps` | Counter | Offset gaps on another context's topic ([Kafka](./kafka#reading-other-contexts-streams)) |

`wow.opentelemetry.sample-interval` (15 s) is how often the sampled gauges, the parked entries and the subscriptions' positions, are read.

## What to alert on

| Signal | Means | Look at |
| --- | --- | --- |
| Readiness down on a node for minutes | A store down, or a node that lost its partitions | `/actuator/health/wow` |
| `wow.subscription.parked` above 0 | An aggregate waits for a fix and an operator | [The Ops Service and Console](./ops-console) |
| `wow.subscription.paused` at 1 for long | A downstream store or service is down | The subscription's logs, the store |
| `wow.subscription.backlog` growing | A subscription falls behind | Its lag, its handler's latency, the node's executor |
| `wow.timer.delay` growing | Timers fire late | The node's load, the timers' store |
| `wow.kafka.offset.gaps` above 0 | A subscription skipped or stopped at retained data | The topic's retention |
| `wow.command.refused` with `wow.command.busy` | Nodes refuse commands under load | The number of nodes, the store's latency |

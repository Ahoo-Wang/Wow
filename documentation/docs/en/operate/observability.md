---
title: Health, Metrics and Tracing
description: The node's health in Actuator's readiness and liveness groups, the metrics and spans wow-observability records, and what to alert on.
outline: deep
---

# Health, Metrics and Tracing

A node tells the platform whether to send it traffic and whether to restart it, through Spring Boot Actuator's health groups. With `wow-observability`, it also records a span for each stage of its work, in the trace of the request, entry or message that started it, and metrics of commits, subscriptions, timers and queries. This page describes both and lists what to alert on.

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
| `node` | The node is not running: starting, draining or stopped; or it met a [fatal error](#fatal-errors), from then on | |
| `stores` | A log, or a store the writes need (one holding a log, a type's primary snapshot store), has kept failing for `wow.health.stores.down-after` (15 s) with no success since | One of them failed within `wow.health.stores.window` (1 min) |
| `secondaryStores` | Never: each store instance off the write path (an additional snapshot store, a query or search store) is `DOWN` by the same rule as `stores`, but its outage degrades the reads it serves, not the node, so readiness leaves it out | It failed within `wow.health.stores.window` (1 min) |
| `ownership` | Its lease store is down, or the node owns none of its share of a lease group's partitions past `wow.health.ownership.handover-grace` (1 min) | It owns another set than its share for that long, or the lease store failed lately |
| `topics` | A topic of another context it reads is still joining (unless `wow.health.topics.required: false`) | It is still joining, with `wow.health.topics.required: false` |
| `subscriptions` | Never | A subscription is `lag-degraded-at` (10,000) entries behind, has `parked-degraded-at` (100) parked entries or more than at the last sample, or has paused a partition |
| `executor` | | The executor runs at `saturation` (90 %) of its in-flight bound |
| `liveness` | The node is stuck for `wow.health.liveness.stall-timeout` (3 min): its dispatcher does not tick, the threads of its backends' driver calls run nothing, a reconcile of its partitions does not end, or commands in flight do not finish while every log answers. A node that is starting fails it only when a reconcile of its partitions has finished no step for that long, a call that never returns (a store call that fails is a step, and fails the start instead), with `stalled: reconcile`, the `group` and `stalledMillis`. Or the node met a [fatal error](#fatal-errors): then until the process ends. This alone fails liveness | |

A degraded component does not change a group's status: it is there to be seen and alerted on. Liveness never fails for a storage outage or while the node drains, so an outage does not restart every node at once. The node samples what a probe must not wait for (the logs' heads, the subscriptions' lag, the parked entries) every `wow.node.sample-interval` (10 s), so a probe answers at once. One sample serves the health and the gauges alike, each subscription by its group and name: every context's `snapshot` subscription is its own, with its own parked count, up to `wow.node.max-parked`.

What a response shows beyond the status follows Actuator's own settings (`management.endpoint.health.show-details`, `never` by default). The details hold counts, states and exception class names, never messages or addresses. [Kubernetes](./kubernetes#probes) shows the probes.

### Fatal errors

A fatal error of the JVM (an `OutOfMemoryError`, an `InternalError`, an `UnknownError`) that the node's own work meets is not a failure of that work: in a subscription's handler, a timer's send, a command's execution, or one of the node's loops (the reconcile and renewals of its leases, its membership, a context's append queue, the ledger's publisher). After one, the JVM's state is not to be trusted, since the heap is the whole process's, and a retry could meet the same error again, so it is neither retried nor parked in the failure ledger. The node is fatal from then on:

- `liveness` and `node` are `DOWN` until the process ends, whatever the node's state: nothing in the process brings them back. Their details hold the first fatal error under `fatal`: the part of the node that met it (`component`: `subscription`, `timer`, `executor`, `ownership`, `membership`, `append-queue`, `ledger`, `node`), what it ran (`name`: the subscription with its group, the context, the lease group), the `partition`, the error's class (`error`) and how long ago (`fatalMillis`); `liveness` adds how many it met (`fatalErrors`).
- The first is logged once at ERROR, with its trace, and each is counted in `wow.node.fatal.errors`.
- The node steps out of every lease group, as the first step of a graceful stop: it leaves the members and hands off its partitions, so the other nodes take them over from the checkpoints (an entry the stopped lane held is handled again there, at least once as always). Its other lanes go on until the hand-off drains them. A hand-off needs memory too: one that takes longer than `wow.cluster.reconcile-timeout` (1 min), or fails, leaves the node renewing no lease, so the others take the partitions once the leases expire (`wow.cluster.lease-ttl`). The gateway stays open and forwards each command it is still given to its partition's new owner. So the work moves even where nothing acts on the liveness, and the host's stop ends the rest when the process goes.

A `StackOverflowError` is not fatal. It unwinds the one thread it happened on, leaving the JVM unharmed, and it happens again on the same input: a handler that overflows its stack fails as one that throws `NotImplementedError` does, retried and then parked. Were it fatal, one such entry would make every node that took its partition over step out in turn.

Run the JVM with `-XX:+ExitOnOutOfMemoryError`, so an `OutOfMemoryError` anywhere in the process, a request's included, ends it at once and its orchestrator starts it again. The chart's default `javaOpts` and the images Wow builds set it ([Kubernetes](./kubernetes#memory)); the node's own rule is the net for a JVM run without it.

## Metrics and traces

Put `wow-observability` on the class path. The spans go through the application's `OpenTelemetry` bean, such as Spring Boot's with an SDK and its exporters, else the global one, such as the Java agent's. The metrics go to the application's Micrometer `MeterRegistry`, such as Actuator's with a Prometheus or OTLP registry, else Micrometer's global registry. `wow.observability.enabled: false` turns both off.

**Spans.** Each stage of a command and of a subscription is a span, in the trace its request, log entry or Kafka message carries:

| Stage | Span |
| --- | --- |
| Admission | `admit <context>.<aggregate>`, an internal span, the child of the request's server span |
| Execution, decision, append | `execute`, `decide`, `append <context>.<aggregate>` |
| A subscription handling an entry of the log | `handle <subscription>` |
| A subscription handling a message of a topic | `process <topic>`, a consumer span |
| A timer firing | `fire <command type>` |
| A query | A span per query |
| A Kafka send | A producer span per send |

The spans carry the context, aggregate, tenant, command and subscription as attributes (`wow.context`, `wow.aggregate.name`, `wow.tenant.id`, `wow.command.name`, `wow.subscription`, …), so a trace follows a command through its sagas across services.

**Metrics.** The main ones, tagged with the spans' attributes (`wow.context`, `wow.aggregate.name`, `wow.subscription`, …):

| Metric | Type | Measures |
| --- | --- | --- |
| `wow.command.duration` | Timer | A command's execution, from taking it to its outcome, by outcome |
| `wow.command.refusals` | Counter | Commands that ended before the kernel took them: refusals, busy lanes or nodes, unavailable stores |
| `wow.command.rejections` | Counter | Commands their aggregate rejected, with or without events, by the rejection's code: business outcomes, not failures |
| `wow.subscription.lag` | Timer | From a commit to a subscription handling it |
| `wow.subscription.backlog` | Gauge | The entries of a partition after a subscription's position |
| `wow.subscription.progress` | Gauge | A subscription's position over the log's head, 0 to 1: a rebuild's progress |
| `wow.subscription.parked.aggregates` | Gauge | The aggregates a subscription has parked |
| `wow.subscription.paused.partitions` | Gauge | The partitions a subscription has paused on a systemic failure |
| `wow.subscription.throttled.partitions` | Gauge | The partitions a subscription throttles on backpressure |
| `wow.subscription.undecodable.messages` | Counter | Messages of a topic this version cannot decode |
| `wow.reservation.losses` | Counter | Unique keys a committed fact lost to another owner after its pending reservation expired |
| `wow.timer.delay` | Timer | How late a timer fires after its due time |
| `wow.query.duration` | Timer | A query, from its admission to its end |
| `wow.query.limit.current` | Gauge | A store's current bound on concurrent queries |
| `wow.subscription.offset.gaps` | Counter | Offset gaps on another context's topic ([Kafka](./kafka#reading-other-contexts-streams)) |

The sampled gauges, the parked entries and the subscriptions' positions, come from the same sample as the health, every `wow.node.sample-interval` (10 s).

**Rejection log.** A command its aggregate rejects, and one admission refuses, are logged in one shape under the logger `wow.command.rejection`: a rejection at INFO, since it is a business outcome, and a refusal at DEBUG, since refusing is admission's ordinary work. The message reads as a sentence, and the fields are SLF4J key-values named as the spans' attributes: `wow.context`, `wow.aggregate.name`, `wow.command.name`, `error.type` (the code), `wow.request.id`, and `wow.with_events` on a rejection. A JSON encoder, such as Logback's or the Elastic Common Schema's, writes them as fields to search by. Set the logger's level to silence rejections, or to `DEBUG` to see refusals too.

## What to alert on

| Signal | Means | Look at |
| --- | --- | --- |
| Readiness down on a node for minutes | A store down, or a node that lost its partitions | `/actuator/health/wow` |
| `wow.subscription.parked.aggregates` above 0 | An aggregate waits for a fix and an operator | [The Ops Service and Console](./ops-console) |
| `wow.subscription.paused.partitions` above 0 for long | A downstream store or service is down | The subscription's logs, the store |
| `wow.subscription.backlog` growing | A subscription falls behind | Its lag, its handler's latency, the node's executor |
| `wow.timer.delay` growing | Timers fire late | The node's load, the timers' store |
| `wow.subscription.offset.gaps` above 0 | A subscription skipped or stopped at retained data | The topic's retention |
| `wow.reservation.losses` above 0 | A unique key two aggregates now both rely on | The error log naming the key and its holder; the reservation time to live |
| `wow.node.fatal.errors` above 0 | A node ran out of memory, or met another fatal error of the JVM, and handed its partitions off; it waits for its restart | Its ERROR log; the heap's size ([Kubernetes](./kubernetes#memory)) |
| `wow.command.refusals` with `error.type=wow.command.busy` | Nodes refuse commands under load | The number of nodes, the store's latency |

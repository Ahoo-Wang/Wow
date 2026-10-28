---
title: Metrics and Spans
description: Each 9.x metric, tag, span and span attribute, and what answers the same question in V10, so dashboards and alerts can be rebuilt before the cut-over.
---

# Metrics and Spans

V10 renames every metric and span of 9.x, and keeps no alias. A dashboard or an alert built on a 9.x name finds nothing on a V10 node: rebuild it from these tables before the [cut-over](./cutover). [Metrics, Spans and Health](../reference/observability) lists every V10 name.

9.x measured each component through one generic timer, `wow.operation`, told apart by its `component` and `operation` tags. V10 names a metric after what it measures, `wow.<domain>.<noun>`, in OpenTelemetry's form, and its tags are the spans' attributes: `wow.context`, `wow.aggregate.name`, `wow.subscription`, `wow.outcome`, and `error.type` for a problem code. The module is `wow-observability` (9.x: `wow-opentelemetry`), and its settings are under `wow.observability.*`.

## Metrics

| 9.x metric | 9.x tags | V10 |
| --- | --- | --- |
| `wow.operation`, `component=command_handler`, `operation=handle` | `context`, `aggregate`, `outcome`, `exception` | `wow.command.duration` (`wow.context`, `wow.aggregate.name`, `wow.outcome`); a command refused before the kernel: `wow.command.refusals` (`error.type`); a command its aggregate rejected: `wow.command.rejections` (`error.type`, the rejection's code) |
| `wow.operation`, `component=event_store`, `operation=append` | `context`, `aggregate`, `outcome` | Inside `wow.command.duration`; the appends waiting: `wow.log.append_queue.depth` |
| `wow.operation`, `component=event_store`, `operation=last` or `single` | `context`, `aggregate` | `wow.state_cache.lookups` (`wow.outcome=miss` reads the store), `wow.log.tail.reads` |
| `wow.operation`, `component=snapshot_store`, `operation=save` or `load` | `context`, `aggregate` | Saving: the `snapshot` subscription's `wow.subscription.lag` and `wow.subscription.backlog`; loading: `wow.state_cache.lookups` |
| `wow.operation`, `component=snapshot_strategy` | `context`, `aggregate` | No equivalent: snapshots are a subscription, measured as any other |
| `wow.operation`, `component=domain_event_handler`, `projection_handler`, `stateless_saga_handler` or `snapshot_handler`, `operation=handle` | `processor`, `subscriber`, `outcome`, `exception` | `wow.subscription.lag` (`wow.subscription`); failures: `wow.subscription.parked.notices` and `wow.subscription.parked.aggregates` |
| `wow.operation`, `operation=send` or `send_if_subscribed` (the buses) | `component`, `message` | Commands: `wow.command.duration`, `wow.command.forward.failures`; events: no metric, the `send <topic>` span |
| `wow.operation`, `operation=receive` (the buses) | `component`, `subscriber` | `wow.subscription.lag`, `wow.subscription.backlog`, `wow.subscription.position` |
| `wow.operation.items` | as `wow.operation` | No equivalent; the queued appends: `wow.log.append_queue.depth` |
| `wow.stream.active`, `wow.stream.messages`, `wow.stream.terminations` | `component`, `subscriber` | No equivalent: a subscription's progress is `wow.subscription.position`, `wow.subscription.backlog`, `wow.subscription.progress`; its pauses `wow.subscription.paused.partitions` |
| `wow.processing.outcomes` | `component`, `outcome`, `exception` | `wow.subscription.parked.notices`, `wow.command.refusals`, `wow.command.rejections` |
| `wow.batch.write`, `wow.batch.write.items`, `wow.batch.queue.wait` | `coordinator`, `lane`, `window` | No equivalent: the append queue is internal; `wow.log.append_queue.depth` and `wow.command.duration` |
| `wow.batch.admission.rejected` | `coordinator`, `reason` | `wow.command.refusals` with `error.type=wow.command.busy` |
| `wow.batch.close`, `wow.batch.coordinator.failed` | `coordinator` | No equivalent: the node's health, `/actuator/health/wow` |
| `wow.query` | `context`, `aggregate`, `outcome` | `wow.query.duration` (`wow.context`, `wow.aggregate.name`, `wow.query.type`, `wow.outcome`) |
| `wow.query.rows` | `context`, `aggregate` | No equivalent |
| `wow.query.schema.refresh`, `wow.query.schema.version.changes` | | No equivalent |

V10 also measures what 9.x did not: the waits (`wow.wait.timeouts`), the executor (`wow.executor.in_flight`, `wow.executor.lanes`), the state cache's evictions, the stores' fallbacks, lost reservations, backpressure, undecodable messages, timers and the query bounds. [Metrics, Spans and Health](../reference/observability#metrics) lists them.

## Spans

9.x had an instrumentation scope per component (`me.ahoo.wow-aggregate`, `me.ahoo.wow-eventStore`, ...). V10 has one, `me.ahoo.wow`, and names a span `<operation> <target>`.

| 9.x span | V10 span |
| --- | --- |
| `<aggregate>.<Command>` (a command handled) | `admit <context>.<aggregate>` (SERVER), with `execute <context>.<aggregate>` and `decide <context>.<aggregate>` under it |
| `<aggregate>.<Command>.command send` | Within the admission; through a topic: `send <topic>` |
| `<aggregate>.<Event>.event.append` | `append <context>.<aggregate>` |
| `<aggregate>.event.load`, `<aggregate>.event.existsRequestId` | No span: part of `execute` |
| `<aggregate>.<Event>.event send`, `<aggregate>.<Event>.state_event send` | `send <topic>` |
| `<function>` (an event processor, projection or saga) | `handle <subscription>`; from a topic: `process <topic>` |
| `<aggregate>.snapshot` | `handle snapshot` |
| `<aggregate>.snapshot.save`, `.snapshot.load`, `.snapshot.version` | No span |
| `<aggregate>.<Command>.waiting` | No span: the wait's outcome ends the admission span (`wow.outcome`) |
| (none) | `fire <command type>` (a timer), `query <context>.<aggregate>` |

## Span attributes

| 9.x attribute | V10 attribute |
| --- | --- |
| `wow.aggregate.context_name` | `wow.context` |
| `wow.aggregate.name` | `wow.aggregate.name` |
| `wow.aggregate.id` | `wow.aggregate.id` |
| `wow.aggregate.tenant_id` | `wow.tenant.id` |
| `wow.message.id` | `wow.command.id` on a command's spans |
| `wow.message.request_id` | `wow.request.id` |
| `wow.message.trace_id` | None: the span's own trace |

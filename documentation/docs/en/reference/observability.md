---
title: Metrics, Spans and Health
description: Every metric a Wow node records, with its type and tags, every span and its attributes, and the components of the node's health.
---

# Metrics, Spans and Health

Every name a Wow node reports to its monitoring. With `wow-observability` on the class path, the metrics go to the application's Micrometer registry and the spans to its `OpenTelemetry`; health is reported through Spring Boot Actuator. [Health, Metrics and Tracing](../operate/observability) explains how to wire them and what to alert on. A test holds each name on this page to the code that reports it. [Metrics and Spans](../migrate/metrics) maps the names of 9.x to these.

## Metrics

Tags are written as `name=value`; a tag without a value takes the value its name says. A tag is named as the span attribute it matches (`wow.*`, or a semantic convention's name where one exists: `error.type`, `messaging.destination.name`), and its values are bounded: no metric carries an identifier such as an aggregate or request id. The partition, `wow.partition`, is on the three gauges of a subscription's position alone.

### Commands

| Metric | Type | Tags | Measures |
| --- | --- | --- | --- |
| `wow.command.duration` | Timer | `wow.context`, `wow.aggregate`, `wow.outcome` | A command's execution, from taking it to its outcome |
| `wow.command.refusals` | Counter | `wow.context`, `wow.aggregate`, `error.type` | Commands that ended before the kernel took them, by their problem code |
| `wow.command.forward.failures` | Counter | `wow.context`, `wow.outcome` | Forwards to the partition's owner without its settlement: `local` (ran here, never reached the owner), `busy` (the owner had its most forwards in flight; ran here), `unknown` (may have reached it) |
| `wow.wait.timeouts` | Counter | `wow.context`, `wow.aggregate`, `wow.stage` | Waits that ran out of time, by the stage they waited for |
| `wow.wait.signal.drops` | Counter | `wow.reason` | Wait signals for another node dropped without being sent |
| `wow.executor.in_flight` | Gauge | `wow.context` | Commands in flight in the context's executor |
| `wow.executor.lanes` | Gauge | `wow.context` | Aggregates with commands in the executor |
| `wow.log.append_queue.depth` | Gauge | `wow.context` | Commits waiting in the log's append queue |
| `wow.log.tail.reads` | Counter | `wow.context`, `wow.reason` | Tails that read the store because memory could not continue them: `start`, `gap`, `overflow`, `idle` |
| `wow.state_cache.lookups` | Counter | `wow.context`, `wow.outcome` | State cache lookups: `hit`, `miss` (a snapshot and a tail read) |
| `wow.state_cache.evictions` | Counter | `wow.context` | States evicted from the state cache |
| `wow.store.fallbacks` | Counter | `wow.context`, `wow.capability`, `wow.reason` | Times a store fell back from an optional capability and did the work the way every store can. `wow.capability`: `snapshot-tail-read` (a log's one-round-trip read of a snapshot with its tail), `snapshot-rebuild` (a snapshot store's in-place rebuild). `wow.reason`: `failed` (the capability failed; the first failure is logged as a warning), `unavailable` (it cannot serve this case, such as a snapshot store in another database than the log), `unsupported` (the store has no such capability) |
| `wow.reservation.losses` | Counter | `wow.context` | Unique keys a committed fact lost to another owner after its pending reservation expired: the kernel's confirm after an append, or the `reservation` subscription's |

### Subscriptions

| Metric | Type | Tags | Measures |
| --- | --- | --- | --- |
| `wow.subscription.lag` | Timer | `wow.context`, `wow.subscription` | From a commit to a subscription handling it |
| `wow.subscription.position` | Gauge | `wow.group`, `wow.subscription`, `wow.partition` | The log position a subscription has handled up to |
| `wow.subscription.backlog` | Gauge | `wow.group`, `wow.subscription`, `wow.partition` | The entries of the partition after that position |
| `wow.subscription.progress` | Gauge | `wow.group`, `wow.subscription`, `wow.partition` | That position over the log's head, 0 to 1: a rebuild's progress |
| `wow.subscription.parked.aggregates` | Gauge | `wow.subscription` | The aggregates the subscription has parked now |
| `wow.subscription.parked.notices` | Counter | `wow.subscription`, `wow.kind` | Entries parked (`wow.kind=parked`), and entries whose automatic retries are spent (`wow.kind=exhausted`) |
| `wow.subscription.paused.partitions` | Gauge | `wow.group`, `wow.subscription` | The subscription's partitions on this node paused on a systemic failure, 0 while all run |
| `wow.subscription.paused.notices` | Counter | `wow.group`, `wow.subscription`, `wow.kind` | Pauses (`wow.kind=paused`) and resumes (`wow.kind=resumed`) |
| `wow.subscription.throttled.partitions` | Gauge | `wow.group`, `wow.subscription` | The subscription's partitions on this node throttled on backpressure, 0 while all run at full speed |
| `wow.subscription.throttled.notices` | Counter | `wow.group`, `wow.subscription`, `wow.kind` | Throttling (`wow.kind=throttled`) and back to full speed (`wow.kind=unthrottled`) |
| `wow.subscription.backpressure.refusals` | Counter | `wow.group`, `wow.subscription`, `error.type` | Handling attempts refused on backpressure, by problem code |
| `wow.subscription.undecodable.messages` | Counter | `messaging.destination.name`, `wow.outcome` | Messages of a topic this version cannot decode, parked (`wow.outcome=parked`) or skipped (`wow.outcome=skipped`) |
| `wow.timer.delay` | Timer | `wow.context` | How late a timer fires after its due time |
| `wow.kafka.offset.gaps` | Counter | | Offset gaps met reading another context's topic |

### Queries

| Metric | Type | Tags | Measures |
| --- | --- | --- | --- |
| `wow.query.duration` | Timer | `wow.context`, `wow.aggregate`, `wow.query.type`, `wow.outcome` | A query, from its admission to its end |
| `wow.query.events.pinned` | Counter | `wow.context`, `wow.aggregate`, `wow.read` | Event queries pinned to their aggregate, read in batches (`wow.read=batch`) or by points (`wow.read=point`) |
| `wow.query.limit.current` | Gauge | `wow.store` | A store's current bound on concurrent queries |
| `wow.query.pressure` | Gauge | `wow.store` | 1 while an adaptive bound is under pressure |
| `wow.query.limit.decreases` | Counter | `wow.store` | The times an adaptive bound went down |

Gauges are sampled every `wow.observability.sample-interval` (15 s), and a gauge whose subject is gone, a subscription that moved to another node say, is removed.

## Spans

The tracer is named `me.ahoo.wow`. A stage that starts from what arrived, a request's trace or a message's propagation fields, is the child of the span they name; any other is the child of the current span. A span ends with its `wow.outcome`, and a failure is recorded on it.

| Span | Kind | Attributes |
| --- | --- | --- |
| `admit <context>.<aggregate>` | SERVER | `wow.context`, `wow.aggregate`, `wow.command` |
| `execute <context>.<aggregate>` | INTERNAL | `wow.context`, `wow.aggregate`, `wow.aggregate.id`, `wow.tenant.id`, `wow.command`, `wow.command.id`, `wow.request.id` |
| `decide <context>.<aggregate>` | INTERNAL | `wow.context`, `wow.aggregate`, `wow.aggregate.id`, `wow.tenant.id` |
| `append <context>.<aggregate>` | INTERNAL | `wow.context`, `wow.aggregate`, `wow.aggregate.id`, `wow.version` |
| `handle <subscription>` | INTERNAL | `wow.subscription`, `wow.context`, `wow.aggregate.id`, `wow.version` |
| `<topic> process` | CONSUMER | `wow.subscription`, `wow.context`, `wow.aggregate.id`, `wow.version`, `messaging.destination.name`, `messaging.operation.type` |
| `<topic> publish` | PRODUCER | `messaging.destination.name`, `messaging.operation.type`, `messaging.batch.message_count` |
| `fire <command type>` | INTERNAL | `wow.context`, `wow.timer.id`, `wow.aggregate.id` |
| `query <context>.<aggregate>` | INTERNAL | `wow.context`, `wow.aggregate`, `wow.query.type` |

`wow.command` is the command's full name, `<context>.<aggregate>.<Name>`, on both the admission and the execution span, so one value finds both.

## Health

With Actuator's health support on the class path, the host contributes three health indicators: `wow`, the node's components; `wowReadiness`; and `wowLiveness`. It adds the last two to Actuator's probe groups, unless the application sets the groups itself:

| Endpoint | Reports |
| --- | --- |
| `/actuator/health/readiness` | `readinessState` and `wowReadiness` |
| `/actuator/health/liveness` | `livenessState` and `wowLiveness` |
| `/actuator/health/wow` | Every component, each `UP`, `DEGRADED` or `DOWN` |

| Component | Settings |
| --- | --- |
| `node` | |
| `stores` | `wow.health.stores.*`: `down-after`, `window` |
| `ownership` | `wow.health.ownership.*`: `handover-grace` |
| `topics` | `wow.health.topics.*`: `required` |
| `subscriptions` | `wow.health.subscriptions.*`: `lag-degraded-at`, `parked-degraded-at` |
| `executor` | `wow.health.executor.*`: `saturation` |
| `liveness` | `wow.health.liveness.*`: `stall-timeout` |
| `queries` | Always `UP`: each store's query bound and its pressure, for reading |

`DEGRADED` never changes a group's status. [Health](../operate/observability#health) says when each component is down or degraded, and which fail readiness or liveness. A node on standby reports its lease groups and topics as `standby`, not down.

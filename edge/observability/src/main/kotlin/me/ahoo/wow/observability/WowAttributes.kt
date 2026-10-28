/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.observability

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The attributes of Wow's spans, named as OpenTelemetry's semantic conventions name attributes: Wow's
 * own under `wow.`, a convention's where one exists (`messaging.*`, `error.type`). The metrics' tags are the same
 * attributes ([WowMetrics]), so a span and a metric of one stage are queried by one name.
 */
internal object WowAttributes {
    /** The instrumentation scope of Wow's spans. */
    const val INSTRUMENTATION: String = "me.ahoo.wow"

    const val CONTEXT: String = "wow.context"
    const val AGGREGATE: String = "wow.aggregate"
    const val AGGREGATE_ID: String = "wow.aggregate.id"
    const val TENANT_ID: String = "wow.tenant.id"
    const val COMMAND: String = "wow.command"
    const val COMMAND_ID: String = "wow.command.id"
    const val REQUEST_ID: String = "wow.request.id"
    const val VERSION: String = "wow.version"
    const val SUBSCRIPTION: String = "wow.subscription"
    const val TIMER_ID: String = "wow.timer.id"
    const val QUERY_TYPE: String = "wow.query.type"
    const val OUTCOME: String = "wow.outcome"

    /** OpenTelemetry's messaging attributes of a topic's span. */
    const val MESSAGING_DESTINATION: String = "messaging.destination.name"
    const val MESSAGING_OPERATION: String = "messaging.operation.type"
    const val MESSAGING_BATCH_COUNT: String = "messaging.batch.message_count"
}

/**
 * The names and tags of Wow's Micrometer metrics, in OpenTelemetry's form:
 *
 * - a name is `wow.<domain>.<noun>`, lower case, dot-separated, snake_case within a segment, and is no other metric's
 *   namespace;
 * - a duration is `.duration`; a counter of events is a plural noun; no name carries a unit or a `.count`/`.total`
 *   suffix, which an exporter adds (Prometheus' `_seconds`, `_total`);
 * - no name is an error code's;
 * - a tag is an attribute of Wow's spans ([WowAttributes]) or of a convention, from the bounded set [TAGS]: never an
 *   identifier ([IDENTIFIERS]), whose values are unbounded.
 */
@InternalWowApi
object WowMetrics {
    /** Timer: a command's execution by the kernel, from taking it to its outcome; tags context, aggregate, outcome. */
    const val COMMAND_DURATION: String = "wow.command.duration"

    /** Timer: from a stream's commit to a subscription handling it; tags context, subscription. */
    const val SUBSCRIPTION_LAG: String = "wow.subscription.lag"

    /** Gauge: the entries a subscription has parked; tag subscription. */
    const val PARKED_AGGREGATES: String = "wow.subscription.parked.aggregates"

    /** Counter: the entries parked, and those whose retries are spent; tags subscription, kind. */
    const val PARKED_NOTICES: String = "wow.subscription.parked.notices"

    /**
     * Gauge: the partitions a subscription has paused on a systemic failure on this node, 0 while it
     * runs them all; tags group, subscription.
     */
    const val PAUSED_PARTITIONS: String = "wow.subscription.paused.partitions"

    /**
     * Counter: the partitions a subscription paused on a systemic failure (kind `paused`) and resumed (kind `resumed`);
     * tags group, subscription, kind.
     */
    const val PAUSED_NOTICES: String = "wow.subscription.paused.notices"

    /**
     * Gauge: the partitions a subscription throttles on backpressure on this node, running fewer
     * handlings at once than its in-flight bound, 0 while it runs them all at full speed; tags group, subscription.
     */
    const val THROTTLED_PARTITIONS: String = "wow.subscription.throttled.partitions"

    /**
     * Counter: the partitions a subscription started throttling on backpressure (kind `throttled`) and stopped
     * (kind `unthrottled`); tags group, subscription, kind.
     */
    const val THROTTLED_NOTICES: String = "wow.subscription.throttled.notices"

    /**
     * Counter: the attempts of a subscription whose downstream pushed back, each retried with backoff;
     * tags group, subscription, error.type (`wow.command.busy`, `wow.wait.timeout`, `wow.query.busy`, or the failure's
     * class).
     */
    const val BACKPRESSURE_REFUSALS: String = "wow.subscription.backpressure.refusals"

    /**
     * Counter: the messages of a topic this version cannot decode; tags messaging.destination.name, and
     * outcome `parked` (its key names an aggregate, parked there) or `skipped` (passed over).
     */
    const val UNDECODABLE_MESSAGES: String = "wow.subscription.undecodable.messages"

    /** Timer: how late a timer fires after its due time; tag context. */
    const val TIMER_DELAY: String = "wow.timer.delay"

    /** Timer: a query, from its admission to its end; tags context, aggregate, query.type, outcome. */
    const val QUERY_DURATION: String = "wow.query.duration"

    /**
     * Counter: the aggregates events queries named and a pinned query policy had checked; tags
     * context, aggregate, read (`batch`: by one snapshot query, `point`: one by one).
     */
    const val EVENTS_PINNED: String = "wow.query.events.pinned"

    /** Gauge: a subscription's watermark in a partition of the log; tags group, subscription, partition. */
    const val POSITION: String = "wow.subscription.position"

    /** Gauge: the entries of a partition of the log after a subscription's watermark; tags as [POSITION]. */
    const val BACKLOG: String = "wow.subscription.backlog"

    /**
     * Gauge: how far a subscription has come in a partition of the log, its watermark over the log's head, from 0 to
     * 1: a rebuild's progress; tags as [POSITION].
     */
    const val PROGRESS: String = "wow.subscription.progress"

    /**
     * Counter: the commands that ended before the kernel took them: admission refusals, busy lanes or
     * nodes, a closing node, an unavailable store, a wait that timed out while the command queued; tags context,
     * aggregate, error.type.
     */
    const val COMMAND_REFUSALS: String = "wow.command.refusals"

    /**
     * Counter: the commands their aggregate's decision rejected, a business outcome rather than a failure, with or
     * without events; tags context, aggregate, error.type (the rejection's code).
     */
    const val COMMAND_REJECTIONS: String = "wow.command.rejections"

    /** Counter: the waits that timed out; tags context, aggregate, stage (the last one reached). */
    const val WAIT_TIMEOUTS: String = "wow.wait.timeouts"

    /** Counter: the wait signals dropped unsent; tag reason. */
    const val SIGNAL_DROPS: String = "wow.wait.signal.drops"

    /** Counter: the forwards that got no settlement from the owner; tags context, outcome (`local`, `busy`, `unknown`). */
    const val FORWARD_FAILURES: String = "wow.command.forward.failures"

    /**
     * Counter: the reads of the store a tail of a context's log made because the node's own commits, handed over in
     * memory, could not continue it; tags context, reason (`start`, `gap`, `overflow`, `idle`).
     */
    const val TAIL_READS: String = "wow.log.tail.reads"

    /** Counter: the kernel's lookups of a state in a context's state cache; tags context, outcome (`hit`, `miss`). */
    const val STATE_CACHE_LOOKUPS: String = "wow.state_cache.lookups"

    /** Counter: the states evicted from a context's state cache; tag context. */
    const val STATE_CACHE_EVICTIONS: String = "wow.state_cache.evictions"

    /**
     * Counter: the keys committed facts lost to another owner after their pending reservations expired;
     * tag context. Each breaks the uniqueness its fact reserved for: alert on it.
     */
    const val RESERVATION_LOSSES: String = "wow.reservation.losses"

    /**
     * Counter: the times a store fell back from an optional capability and did the work the way every
     * store can; tags context, capability (`snapshot-tail-read`, `snapshot-rebuild`), reason (`failed`, `unavailable`,
     * `unsupported`). A `failed` one is a store that failed where it should not: look at the warning it logged first.
     */
    const val STORE_FALLBACKS: String = "wow.store.fallbacks"

    /** Gauge: the commands in a context's executor, waiting in their lanes or in the kernel; tag context. */
    const val EXECUTOR_IN_FLIGHT: String = "wow.executor.in_flight"

    /** Gauge: the aggregates of a context with commands in flight; tag context. */
    const val EXECUTOR_LANES: String = "wow.executor.lanes"

    /** Gauge: the appends queued on a context's partition append queue; tag context. */
    const val APPEND_QUEUE_DEPTH: String = "wow.log.append_queue.depth"

    /** Gauge: how many queries a store runs at once now, its query bulkhead's limit; tag store. */
    const val QUERY_LIMIT_CURRENT: String = "wow.query.limit.current"

    /** Gauge: whether a store's writes pressed in the last period, 1 or 0; tag store. */
    const val QUERY_PRESSURE: String = "wow.query.pressure"

    /** Counter: how many times a store's query limit was halved for its writes; tag store. */
    const val QUERY_LIMIT_DECREASES: String = "wow.query.limit.decreases"

    /** Counter: the offset gaps the node met reading another context's topic; no tags. */
    const val OFFSET_GAPS: String = "wow.kafka.offset.gaps"

    /** Every metric above. */
    val NAMES: List<String> = listOf(
        COMMAND_DURATION, SUBSCRIPTION_LAG, PARKED_AGGREGATES, PARKED_NOTICES, PAUSED_PARTITIONS, PAUSED_NOTICES,
        THROTTLED_PARTITIONS, THROTTLED_NOTICES, BACKPRESSURE_REFUSALS, UNDECODABLE_MESSAGES, TIMER_DELAY,
        QUERY_DURATION, EVENTS_PINNED, POSITION, BACKLOG, PROGRESS, COMMAND_REFUSALS, COMMAND_REJECTIONS, WAIT_TIMEOUTS, SIGNAL_DROPS,
        FORWARD_FAILURES, TAIL_READS, STATE_CACHE_LOOKUPS, STATE_CACHE_EVICTIONS, RESERVATION_LOSSES, STORE_FALLBACKS,
        EXECUTOR_IN_FLIGHT, EXECUTOR_LANES, APPEND_QUEUE_DEPTH, QUERY_LIMIT_CURRENT, QUERY_PRESSURE,
        QUERY_LIMIT_DECREASES, OFFSET_GAPS,
    )

    const val STORE: String = "wow.store"
    const val CONTEXT: String = WowAttributes.CONTEXT
    const val AGGREGATE: String = WowAttributes.AGGREGATE
    const val SUBSCRIPTION: String = WowAttributes.SUBSCRIPTION
    const val OUTCOME: String = WowAttributes.OUTCOME
    const val QUERY_TYPE: String = WowAttributes.QUERY_TYPE
    const val STAGE: String = "wow.stage"
    const val REASON: String = "wow.reason"
    const val CAPABILITY: String = "wow.capability"
    const val KIND: String = "wow.kind"
    const val GROUP: String = "wow.group"
    const val PARTITION: String = "wow.partition"
    const val READ: String = "wow.read"

    /** OpenTelemetry's attribute of an error's type: the problem code of a refusal. */
    const val ERROR_TYPE: String = "error.type"

    /** The topic a message came from. */
    const val TOPIC: String = WowAttributes.MESSAGING_DESTINATION

    /**
     * The tags a metric may carry: attributes whose values are bounded (contexts, aggregate types, subscriptions,
     * stores, codes and outcomes). [PARTITION] is on the per-partition gauges of a subscription's position alone.
     */
    val TAGS: Set<String> = setOf(
        STORE, CONTEXT, AGGREGATE, SUBSCRIPTION, OUTCOME, QUERY_TYPE, STAGE, REASON, CAPABILITY, KIND, GROUP, PARTITION,
        READ, ERROR_TYPE, TOPIC,
    )

    /** The attributes of a span no metric carries: one value per aggregate, command, request or timer. */
    val IDENTIFIERS: Set<String> = setOf(
        WowAttributes.AGGREGATE_ID,
        WowAttributes.TENANT_ID,
        WowAttributes.COMMAND,
        WowAttributes.COMMAND_ID,
        WowAttributes.REQUEST_ID,
        WowAttributes.VERSION,
        WowAttributes.TIMER_ID,
    )

    const val BATCH_READ: String = "batch"
    const val POINT_READ: String = "point"
    const val PARKED_OUTCOME: String = "parked"
    const val SKIPPED_OUTCOME: String = "skipped"
    const val PAUSED_KIND: String = "paused"
    const val RESUMED_KIND: String = "resumed"
    const val THROTTLED_KIND: String = "throttled"
    const val UNTHROTTLED_KIND: String = "unthrottled"
}

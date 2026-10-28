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

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.runtime.subscription.ParkedNotice
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedOutcome

/**
 * The attributes of Wow's spans, named as OpenTelemetry's semantic conventions name attributes: Wow's
 * own under `wow.`, a convention's where one exists (`messaging.*`, `error.type`). The metrics' tags are the same
 * attributes ([WowMetrics]), so a span and a metric of one stage are queried by one name. No attribute's name is
 * another's namespace: the aggregate type is `wow.aggregate.name` beside `wow.aggregate.id`.
 */
internal object WowAttributes {
    /** The instrumentation scope of Wow's spans. */
    const val INSTRUMENTATION: String = "me.ahoo.wow"

    /** The version of the instrumentation scope: Wow's, as its jar names it; null outside a jar. */
    val INSTRUMENTATION_VERSION: String? = WowAttributes::class.java.`package`?.implementationVersion

    const val CONTEXT: String = "wow.context"
    const val AGGREGATE: String = "wow.aggregate.name"
    const val AGGREGATE_ID: String = "wow.aggregate.id"
    const val VERSION: String = "wow.aggregate.version"
    const val DATASET: String = "wow.dataset"
    const val TENANT_ID: String = "wow.tenant.id"
    const val COMMAND: String = "wow.command.name"
    const val COMMAND_ID: String = "wow.command.id"
    const val REQUEST_ID: String = "wow.request.id"
    const val SUBSCRIPTION: String = "wow.subscription"
    const val TIMER_ID: String = "wow.timer.id"
    const val QUERY_TYPE: String = "wow.query.type"
    const val OUTCOME: String = "wow.outcome"

    /** OpenTelemetry's messaging attributes of a topic's span. */
    const val MESSAGING_DESTINATION: String = "messaging.destination.name"
    const val MESSAGING_OPERATION: String = "messaging.operation.type"
    const val MESSAGING_BATCH_COUNT: String = "messaging.batch.message_count"

    /** Every attribute of a span above. */
    val NAMES: List<String> = listOf(
        CONTEXT, AGGREGATE, AGGREGATE_ID, VERSION, DATASET, TENANT_ID, COMMAND, COMMAND_ID, REQUEST_ID, SUBSCRIPTION,
        TIMER_ID, QUERY_TYPE, OUTCOME, MESSAGING_DESTINATION, MESSAGING_OPERATION, MESSAGING_BATCH_COUNT,
    )
}

/** Wow's tracer of [this]: the scope [WowAttributes.INSTRUMENTATION] at Wow's version. */
internal fun OpenTelemetry.wowTracer(): Tracer = tracerBuilder(WowAttributes.INSTRUMENTATION)
    .apply { WowAttributes.INSTRUMENTATION_VERSION?.let(::setInstrumentationVersion) }
    .build()

/**
 * The catalog of Wow's Micrometer metrics, each a [MetricSpec] that registers it, in OpenTelemetry's form:
 *
 * - a name is `wow.<domain>.<noun>`, lower case, dot-separated, snake_case within a segment, and is no other metric's
 *   namespace;
 * - a duration is `.duration`; a counter of events is a plural noun; no name carries a unit or a `.count`/`.total`
 *   suffix, which an exporter adds (Prometheus' `_seconds`, `_total`);
 * - no name is an error code's;
 * - a metric's tags are its own, each an attribute of Wow's spans ([WowAttributes]) or of a convention whose values
 *   are bounded: never an identifier ([IDENTIFIERS]). A tag whose values the framework names (an outcome, a kind, a
 *   reason) lists them ([MetricSpec.values]), lower case, one word per concept.
 */
@InternalWowApi
object WowMetrics {
    const val STORE: String = "wow.store"
    const val CONTEXT: String = WowAttributes.CONTEXT
    const val AGGREGATE: String = WowAttributes.AGGREGATE
    const val DATASET: String = WowAttributes.DATASET
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

    const val BATCH_READ: String = "batch"
    const val POINT_READ: String = "point"
    const val PARKED_OUTCOME: String = "parked"
    const val SKIPPED_OUTCOME: String = "skipped"
    const val PAUSED_KIND: String = "paused"
    const val RESUMED_KIND: String = "resumed"
    const val THROTTLED_KIND: String = "throttled"
    const val UNTHROTTLED_KIND: String = "unthrottled"

    /** A query's type as its metric and its span name it. */
    val QueryType.tagValue: String get() = name.lowercase()

    /** A wait's stage as its metric names it. */
    val Stage.tagValue: String get() = name.lowercase()

    /** A parked notice's kind as its metric names it. */
    val ParkedNotice.Kind.tagValue: String get() = name.lowercase()

    private val QUERY_OUTCOMES = listOf(
        ObservedOutcome.OK,
        ObservedOutcome.CACHED,
        ObservedOutcome.ERROR,
        ObservedOutcome.CANCELLED
    )
        .map { it.value }
    private val QUERY_TYPES = QueryType.entries.map { it.tagValue }

    val COMMAND_DURATION: MetricSpec = MetricSpec.timer(
        "wow.command.duration",
        "A command's execution by the kernel, from taking it to its outcome.",
        tags = listOf(CONTEXT, AGGREGATE, OUTCOME),
        values = mapOf(
            OUTCOME to listOf(
                ObservedOutcome.COMMITTED,
                ObservedOutcome.REJECTED,
                ObservedOutcome.DUPLICATE,
                ObservedOutcome.UNCHANGED,
                ObservedOutcome.CONTENDED,
                ObservedOutcome.UNKNOWN,
                ObservedOutcome.OK,
                ObservedOutcome.ERROR,
                ObservedOutcome.CANCELLED,
            ).map { it.value },
        ),
    )

    val COMMAND_REFUSALS: MetricSpec = MetricSpec.counter(
        "wow.command.refusals",
        "The commands that ended before the kernel took them: admission refusals, busy lanes or nodes, a closing " +
            "node, an unavailable store, a wait that timed out while the command queued; by problem code.",
        tags = listOf(CONTEXT, AGGREGATE, ERROR_TYPE),
    )

    val COMMAND_REJECTIONS: MetricSpec = MetricSpec.counter(
        "wow.command.rejections",
        "The commands their aggregate's decision rejected, a business outcome rather than a failure, with or without " +
            "events; by the rejection's code.",
        tags = listOf(CONTEXT, AGGREGATE, ERROR_TYPE),
    )

    val FORWARD_FAILURES: MetricSpec = MetricSpec.counter(
        "wow.command.forward.failures",
        "The forwards that got no settlement from the partition's owner.",
        tags = listOf(CONTEXT, OUTCOME),
        values = mapOf(OUTCOME to ObservedEvent.ForwardFailed.Outcome.entries.map { it.value }),
    )

    val WAIT_TIMEOUTS: MetricSpec = MetricSpec.counter(
        "wow.wait.timeouts",
        "The waits that timed out, by the last stage they reached.",
        tags = listOf(CONTEXT, AGGREGATE, STAGE),
        values = mapOf(STAGE to Stage.entries.map { it.tagValue }),
    )

    val SIGNAL_DROPS: MetricSpec = MetricSpec.counter(
        "wow.wait.signal.drops",
        "The wait signals for another node dropped unsent.",
        tags = listOf(REASON),
        values = mapOf(REASON to ObservedEvent.SignalDropped.Reason.entries.map { it.value }),
    )

    val EXECUTOR_IN_FLIGHT: MetricSpec = MetricSpec.gauge(
        "wow.executor.in_flight",
        "The commands in a context's executor, waiting in their lanes or in the kernel.",
        tags = listOf(CONTEXT),
    )

    val EXECUTOR_LANES: MetricSpec = MetricSpec.gauge(
        "wow.executor.lanes",
        "The aggregates of a context with commands in flight.",
        tags = listOf(CONTEXT),
    )

    val APPEND_QUEUE_DEPTH: MetricSpec = MetricSpec.gauge(
        "wow.log.append_queue.depth",
        "The appends queued on a context's partition append queue.",
        tags = listOf(CONTEXT),
    )

    val TAIL_READS: MetricSpec = MetricSpec.counter(
        "wow.log.tail.reads",
        "The reads of the store a tail of a context's log made because the node's own commits, handed over in " +
            "memory, could not continue it.",
        tags = listOf(CONTEXT, REASON),
        values = mapOf(REASON to ObservedEvent.TailRead.Reason.entries.map { it.value }),
    )

    val STATE_CACHE_LOOKUPS: MetricSpec = MetricSpec.counter(
        "wow.state_cache.lookups",
        "The kernel's lookups of a state in a context's state cache.",
        tags = listOf(CONTEXT, OUTCOME),
        values = mapOf(
            OUTCOME to listOf(ObservedEvent.StateCache.Outcome.HIT, ObservedEvent.StateCache.Outcome.MISS).map { it.value },
        ),
    )

    val STATE_CACHE_EVICTIONS: MetricSpec = MetricSpec.counter(
        "wow.state_cache.evictions",
        "The states evicted from a context's state cache.",
        tags = listOf(CONTEXT),
    )

    val STORE_FALLBACKS: MetricSpec = MetricSpec.counter(
        "wow.store.fallbacks",
        "The times a store fell back from an optional capability and did the work the way every store can. A " +
            "failed one is a store that failed where it should not: look at the warning it logged first.",
        tags = listOf(CONTEXT, CAPABILITY, REASON),
        values = mapOf(
            CAPABILITY to ObservedEvent.StoreFellBack.Capability.entries.map { it.value },
            REASON to ObservedEvent.StoreFellBack.Reason.entries.map { it.value },
        ),
    )

    val RESERVATION_LOSSES: MetricSpec = MetricSpec.counter(
        "wow.reservation.losses",
        "The keys committed facts lost to another owner after their pending reservations expired. Each breaks the " +
            "uniqueness its fact reserved for: alert on it.",
        tags = listOf(CONTEXT),
    )

    val SUBSCRIPTION_LAG: MetricSpec = MetricSpec.timer(
        "wow.subscription.lag",
        "From a stream's commit to a subscription handling it.",
        tags = listOf(CONTEXT, SUBSCRIPTION),
    )

    val POSITION: MetricSpec = MetricSpec.gauge(
        "wow.subscription.position",
        "A subscription's watermark in a partition of the log.",
        tags = listOf(GROUP, SUBSCRIPTION, PARTITION),
    )

    val BACKLOG: MetricSpec = MetricSpec.gauge(
        "wow.subscription.backlog",
        "The entries of a partition of the log after a subscription's watermark.",
        tags = listOf(GROUP, SUBSCRIPTION, PARTITION),
    )

    val PROGRESS: MetricSpec = MetricSpec.gauge(
        "wow.subscription.progress",
        "How far a subscription has come in a partition of the log, its watermark over the log's head, from 0 to 1: " +
            "a rebuild's progress.",
        tags = listOf(GROUP, SUBSCRIPTION, PARTITION),
    )

    val PARKED_AGGREGATES: MetricSpec = MetricSpec.gauge(
        "wow.subscription.parked.aggregates",
        "The entries a subscription has parked.",
        tags = listOf(SUBSCRIPTION),
    )

    val PARKED_NOTICES: MetricSpec = MetricSpec.counter(
        "wow.subscription.parked.notices",
        "The entries parked, and those whose automatic retries are spent.",
        tags = listOf(SUBSCRIPTION, KIND),
        values = mapOf(KIND to ParkedNotice.Kind.entries.map { it.tagValue }),
    )

    val PAUSED_PARTITIONS: MetricSpec = MetricSpec.gauge(
        "wow.subscription.paused.partitions",
        "The partitions a subscription has paused on a systemic failure on this node, 0 while it runs them all.",
        tags = listOf(GROUP, SUBSCRIPTION),
    )

    val PAUSED_NOTICES: MetricSpec = MetricSpec.counter(
        "wow.subscription.paused.notices",
        "The partitions a subscription paused on a systemic failure, and resumed.",
        tags = listOf(GROUP, SUBSCRIPTION, KIND),
        values = mapOf(KIND to listOf(PAUSED_KIND, RESUMED_KIND)),
    )

    val THROTTLED_PARTITIONS: MetricSpec = MetricSpec.gauge(
        "wow.subscription.throttled.partitions",
        "The partitions a subscription throttles on backpressure on this node, running fewer handlings at once than " +
            "its in-flight bound, 0 while it runs them all at full speed.",
        tags = listOf(GROUP, SUBSCRIPTION),
    )

    val THROTTLED_NOTICES: MetricSpec = MetricSpec.counter(
        "wow.subscription.throttled.notices",
        "The partitions a subscription started throttling on backpressure, and stopped.",
        tags = listOf(GROUP, SUBSCRIPTION, KIND),
        values = mapOf(KIND to listOf(THROTTLED_KIND, UNTHROTTLED_KIND)),
    )

    val BACKPRESSURE_REFUSALS: MetricSpec = MetricSpec.counter(
        "wow.subscription.backpressure.refusals",
        "The attempts of a subscription whose downstream pushed back, each retried with backoff; by the downstream's " +
            "problem code, or the failure's class.",
        tags = listOf(GROUP, SUBSCRIPTION, ERROR_TYPE),
    )

    val UNDECODABLE_MESSAGES: MetricSpec = MetricSpec.counter(
        "wow.subscription.undecodable.messages",
        "The messages of a topic this version cannot decode: parked when its key names an aggregate, else skipped.",
        tags = listOf(TOPIC, OUTCOME),
        values = mapOf(OUTCOME to listOf(PARKED_OUTCOME, SKIPPED_OUTCOME)),
    )

    val OFFSET_GAPS: MetricSpec = MetricSpec.counter(
        "wow.kafka.offset.gaps",
        "The offsets a read of another context's topic was to start from that its partition no longer holds.",
        tags = emptyList(),
    )

    val TIMER_DELAY: MetricSpec = MetricSpec.timer(
        "wow.timer.delay",
        "How late a timer fires after its due time.",
        tags = listOf(CONTEXT),
    )

    val QUERY_DURATION: MetricSpec = MetricSpec.timer(
        "wow.query.duration",
        "A query of an aggregate type's snapshots or events, from its admission to its end.",
        tags = listOf(CONTEXT, AGGREGATE, QUERY_TYPE, OUTCOME),
        values = mapOf(
            QUERY_TYPE to QUERY_TYPES,
            OUTCOME to QUERY_OUTCOMES,
        ),
    )

    val DATASET_QUERY_DURATION: MetricSpec = MetricSpec.timer(
        "wow.query.dataset.duration",
        "A query of a dataset, from its admission to its end.",
        tags = listOf(CONTEXT, DATASET, QUERY_TYPE, OUTCOME),
        values = mapOf(
            QUERY_TYPE to QUERY_TYPES,
            OUTCOME to QUERY_OUTCOMES,
        ),
    )

    val EVENTS_PINNED: MetricSpec = MetricSpec.counter(
        "wow.query.events.pinned",
        "The aggregates events queries named and a pinned query policy had checked, by how they were read: by one " +
            "snapshot query, or one by one.",
        tags = listOf(CONTEXT, AGGREGATE, READ),
        values = mapOf(READ to listOf(BATCH_READ, POINT_READ)),
    )

    val QUERY_LIMIT_CURRENT: MetricSpec = MetricSpec.gauge(
        "wow.query.limit.current",
        "How many queries a store runs at once now, its query bulkhead's limit.",
        tags = listOf(STORE),
    )

    val QUERY_PRESSURE: MetricSpec = MetricSpec.gauge(
        "wow.query.pressure",
        "Whether a store's writes pressed in the last period, 1 or 0.",
        tags = listOf(STORE),
    )

    val QUERY_LIMIT_DECREASES: MetricSpec = MetricSpec.counter(
        "wow.query.limit.decreases",
        "How many times a store's query limit was halved for its writes.",
        tags = listOf(STORE),
    )

    /** Every metric above. */
    val ALL: List<MetricSpec> = listOf(
        COMMAND_DURATION, COMMAND_REFUSALS, COMMAND_REJECTIONS, FORWARD_FAILURES, WAIT_TIMEOUTS, SIGNAL_DROPS,
        EXECUTOR_IN_FLIGHT, EXECUTOR_LANES, APPEND_QUEUE_DEPTH, TAIL_READS, STATE_CACHE_LOOKUPS, STATE_CACHE_EVICTIONS,
        STORE_FALLBACKS, RESERVATION_LOSSES, SUBSCRIPTION_LAG, POSITION, BACKLOG, PROGRESS, PARKED_AGGREGATES,
        PARKED_NOTICES, PAUSED_PARTITIONS, PAUSED_NOTICES, THROTTLED_PARTITIONS, THROTTLED_NOTICES,
        BACKPRESSURE_REFUSALS, UNDECODABLE_MESSAGES, OFFSET_GAPS, TIMER_DELAY, QUERY_DURATION, DATASET_QUERY_DURATION,
        EVENTS_PINNED, QUERY_LIMIT_CURRENT, QUERY_PRESSURE, QUERY_LIMIT_DECREASES,
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
}

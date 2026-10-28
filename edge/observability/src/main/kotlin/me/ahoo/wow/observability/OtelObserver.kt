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

import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.kernel.StateCacheOutcome
import me.ahoo.wow.observability.WowAttributes.AGGREGATE
import me.ahoo.wow.observability.WowAttributes.AGGREGATE_ID
import me.ahoo.wow.observability.WowAttributes.COMMAND
import me.ahoo.wow.observability.WowAttributes.COMMAND_ID
import me.ahoo.wow.observability.WowAttributes.CONTEXT
import me.ahoo.wow.observability.WowAttributes.MESSAGING_DESTINATION
import me.ahoo.wow.observability.WowAttributes.MESSAGING_OPERATION
import me.ahoo.wow.observability.WowAttributes.REQUEST_ID
import me.ahoo.wow.observability.WowAttributes.SUBSCRIPTION
import me.ahoo.wow.observability.WowAttributes.TENANT_ID
import me.ahoo.wow.observability.WowAttributes.TIMER_ID
import me.ahoo.wow.observability.WowAttributes.VERSION
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.timer.Timer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The core's [Observer] on OpenTelemetry and Micrometer: a span for each stage the core observes, in
 * the trace its work belongs to, and the stage's metrics in [meters].
 *
 * | Stage | Span | Kind | Metric |
 * |---|---|---|---|
 * | admission | `admit <context>.<aggregate>` | SERVER | |
 * | execution | `execute <context>.<aggregate>` | INTERNAL | [WowMetrics.COMMAND_DURATION] |
 * | decision | `decide <context>.<aggregate>` | INTERNAL | |
 * | append | `append <context>.<aggregate>` | INTERNAL | |
 * | handling, of the log | `handle <subscription>` | INTERNAL | [WowMetrics.SUBSCRIPTION_LAG] |
 * | handling, of a topic | `<topic> process` | CONSUMER | [WowMetrics.SUBSCRIPTION_LAG] |
 * | timer firing | `fire <command type>` | INTERNAL | [WowMetrics.TIMER_DELAY] |
 *
 * A subscription's partition paused on a systemic failure, and resumed, counts in
 * [WowMetrics.PAUSED_NOTICES]; [NodeMetrics] samples [WowMetrics.PAUSED_PARTITIONS]. One throttled on backpressure, and back at
 * full speed, counts in [WowMetrics.THROTTLED_NOTICES], each backpressured attempt in [WowMetrics.BACKPRESSURE_REFUSALS];
 * [NodeMetrics] samples [WowMetrics.THROTTLED_PARTITIONS].
 *
 * A stage that starts from what arrived (a request's trace, an entry's or a message's [me.ahoo.wow.spi.log.Transport.trace])
 * is the child of the span those propagation fields name, as [openTelemetry]'s propagators extract them; any other is
 * the child of the current span. [clock] is the wall clock the lag and the timer delay are measured by.
 */
@InternalWowApi
class OtelObserver(
    openTelemetry: OpenTelemetry,
    meters: MeterRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
) : Observer {
    private val tracer = openTelemetry.getTracer(WowAttributes.INSTRUMENTATION)
    private val propagator = openTelemetry.propagators.textMapPropagator
    private val cached = MeterCache(meters)

    override fun admitting(context: String, aggregate: String, command: String, trace: Map<String, String>): Observation {
        val parent = OtelTraceContext.extract(propagator, Context.current(), trace)
        val span = tracer.spanBuilder("admit $context.$aggregate")
            .setSpanKind(SpanKind.SERVER)
            .setAttribute(CONTEXT, context)
            .setAttribute(AGGREGATE, aggregate)
            .setAttribute(COMMAND, commandName(context, aggregate, command))
        return observation(parent, span)
    }

    /**
     * The execution span carries [commandType], the kernel's resolved name (`context.aggregate.Name`, `@Name`
     * included), as the admission span carries the request's command under its type: one value for
     * `wow.command` on both.
     */
    override fun executing(command: Command<*>, commandType: String): Observation {
        val type = command.ref.type
        val span = commandSpan("execute", command)
            .setAttribute(COMMAND_ID, command.id)
            .setAttribute(REQUEST_ID, command.requestId)
            .setAttribute(COMMAND, commandType)
        return observation(Context.current(), span) { outcome, nanos ->
            cached.timer(
                WowMetrics.COMMAND_DURATION,
                WowMetrics.CONTEXT,
                type.context,
                WowMetrics.AGGREGATE,
                type.name,
                WowMetrics.OUTCOME,
                outcome,
            ).record(nanos, TimeUnit.NANOSECONDS)
        }
    }

    override fun observed(event: ObservedEvent) {
        when (event) {
            is ObservedEvent.Refused -> refused(event.type, event.code)
            is ObservedEvent.WaitTimedOut -> waitTimedOut(event.type, event.stage)
            is ObservedEvent.SignalDropped -> signalDropped(event.reason)
            is ObservedEvent.ForwardFailed -> forwardFailed(event.type, event.outcome)
            is ObservedEvent.StateCache -> stateCache(event.context, event.outcome)
            is ObservedEvent.ReservationLost -> reservationLost(event.context)
            is ObservedEvent.StoreFellBack -> storeFellBack(event.context, event.capability, event.reason)
            is ObservedEvent.PartitionPaused -> pausedNotice(event.group, event.subscription, WowMetrics.PAUSED_KIND)
            is ObservedEvent.PartitionResumed -> pausedNotice(event.group, event.subscription, WowMetrics.RESUMED_KIND)
            is ObservedEvent.PartitionThrottled -> partitionThrottled(event.group, event.subscription, event.throttled)
            is ObservedEvent.Backpressured -> backpressured(event.group, event.subscription, event.code)
            is ObservedEvent.TailRead -> tailRead(event.context, event.reason)
            is ObservedEvent.Undecodable -> undecodable(event.topic, event.parked)
        }
    }

    private fun undecodable(topic: String, parked: Boolean) {
        cached.counter(
            WowMetrics.UNDECODABLE_MESSAGES,
            WowMetrics.TOPIC,
            topic,
            WowMetrics.OUTCOME,
            if (parked) WowMetrics.PARKED_OUTCOME else WowMetrics.SKIPPED_OUTCOME,
        ).increment()
    }

    private fun partitionThrottled(group: String, subscription: String, throttled: Boolean) {
        cached.counter(
            WowMetrics.THROTTLED_NOTICES,
            WowMetrics.GROUP,
            group,
            WowMetrics.SUBSCRIPTION,
            subscription,
            WowMetrics.KIND,
            if (throttled) WowMetrics.THROTTLED_KIND else WowMetrics.UNTHROTTLED_KIND,
        ).increment()
    }

    private fun backpressured(group: String, subscription: String, code: String) {
        cached.counter(
            WowMetrics.BACKPRESSURE_REFUSALS,
            WowMetrics.GROUP,
            group,
            WowMetrics.SUBSCRIPTION,
            subscription,
            WowMetrics.ERROR_TYPE,
            code,
        ).increment()
    }

    private fun pausedNotice(group: String, subscription: String, kind: String) {
        cached.counter(
            WowMetrics.PAUSED_NOTICES,
            WowMetrics.GROUP,
            group,
            WowMetrics.SUBSCRIPTION,
            subscription,
            WowMetrics.KIND,
            kind,
        ).increment()
    }

    private fun refused(type: AggregateType, code: String) {
        cached.counter(
            WowMetrics.COMMAND_REFUSALS,
            WowMetrics.CONTEXT,
            type.context,
            WowMetrics.AGGREGATE,
            type.name,
            WowMetrics.ERROR_TYPE,
            code,
        ).increment()
    }

    private fun waitTimedOut(type: AggregateType, stage: Stage) {
        cached.counter(
            WowMetrics.WAIT_TIMEOUTS,
            WowMetrics.CONTEXT,
            type.context,
            WowMetrics.AGGREGATE,
            type.name,
            WowMetrics.STAGE,
            stage.name.lowercase(),
        ).increment()
    }

    private fun signalDropped(reason: String) {
        cached.counter(WowMetrics.SIGNAL_DROPS, WowMetrics.REASON, reason).increment()
    }

    private fun forwardFailed(type: AggregateType, outcome: String) {
        cached.counter(WowMetrics.FORWARD_FAILURES, WowMetrics.CONTEXT, type.context, WowMetrics.OUTCOME, outcome)
            .increment()
    }

    private fun stateCache(context: String, outcome: String) {
        if (outcome == StateCacheOutcome.EVICTED) {
            cached.counter(WowMetrics.STATE_CACHE_EVICTIONS, WowMetrics.CONTEXT, context).increment()
        } else {
            cached.counter(WowMetrics.STATE_CACHE_LOOKUPS, WowMetrics.CONTEXT, context, WowMetrics.OUTCOME, outcome)
                .increment()
        }
    }

    private fun reservationLost(context: String) {
        cached.counter(WowMetrics.RESERVATION_LOSSES, WowMetrics.CONTEXT, context).increment()
    }

    private fun storeFellBack(context: String, capability: String, reason: String) {
        cached.counter(
            WowMetrics.STORE_FALLBACKS,
            WowMetrics.CONTEXT,
            context,
            WowMetrics.CAPABILITY,
            capability,
            WowMetrics.REASON,
            reason,
        ).increment()
    }

    private fun tailRead(context: String, reason: String) {
        cached.counter(WowMetrics.TAIL_READS, WowMetrics.CONTEXT, context, WowMetrics.REASON, reason).increment()
    }

    override fun deciding(command: Command<*>): Observation =
        observation(Context.current(), commandSpan("decide", command))

    override fun appending(stream: EventStreamRecord): Observation {
        val type = stream.ref.type
        val span = tracer.spanBuilder("append $type")
            .setAttribute(CONTEXT, type.context)
            .setAttribute(AGGREGATE, type.name)
            .setAttribute(AGGREGATE_ID, stream.ref.id)
            .setAttribute(VERSION, stream.version.toLong())
        return observation(Context.current(), span)
    }

    override fun handling(
        subscription: String,
        context: String,
        stream: EventStreamRecord,
        trace: Map<String, String>,
        topic: String?,
    ): Observation {
        val parent = OtelTraceContext.extract(propagator, Context.current(), trace)
        val span = if (topic == null) {
            tracer.spanBuilder("handle $subscription")
        } else {
            tracer.spanBuilder("$topic process")
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute(MESSAGING_DESTINATION, topic)
                .setAttribute(MESSAGING_OPERATION, "process")
        }
            .setAttribute(SUBSCRIPTION, subscription)
            .setAttribute(CONTEXT, context)
            .setAttribute(AGGREGATE_ID, stream.ref.id)
            .setAttribute(VERSION, stream.version.toLong())
        // A message of a command or receipts topic commits nothing: it has no commit time to lag behind.
        if (stream.commitTime > 0) {
            val lag = (clock() - stream.commitTime).coerceAtLeast(0)
            cached.timer(
                WowMetrics.SUBSCRIPTION_LAG,
                WowMetrics.CONTEXT,
                context,
                WowMetrics.SUBSCRIPTION,
                subscription
            )
                .record(lag, TimeUnit.MILLISECONDS)
        }
        return observation(parent, span)
    }

    override fun firing(timer: Timer): Observation {
        val context = timer.target.type.context
        val delay = (clock() - timer.dueAt).coerceAtLeast(0)
        cached.timer(WowMetrics.TIMER_DELAY, WowMetrics.CONTEXT, context).record(delay, TimeUnit.MILLISECONDS)
        val span = tracer.spanBuilder("fire ${timer.commandType}")
            .setAttribute(CONTEXT, context)
            .setAttribute(TIMER_ID, timer.id)
            .setAttribute(AGGREGATE_ID, timer.target.id)
        return observation(Context.current(), span)
    }

    override fun resuming(trace: Map<String, String>): CoroutineContext = if (trace.isEmpty()) {
        EmptyCoroutineContext
    } else {
        OtelTraceContext(OtelTraceContext.extract(propagator, Context.current(), trace), propagator)
    }

    /** `context.aggregate.Name` of [command], which a request may name with or without its type. */
    private fun commandName(context: String, aggregate: String, command: String): String =
        "$context.$aggregate.${command.substringAfterLast('.')}"

    private fun commandSpan(stage: String, command: Command<*>): SpanBuilder {
        val ref = command.ref
        return tracer.spanBuilder("$stage ${ref.type}")
            .setAttribute(CONTEXT, ref.type.context)
            .setAttribute(AGGREGATE, ref.type.name)
            .setAttribute(AGGREGATE_ID, ref.id)
            .setAttribute(TENANT_ID, ref.tenantId)
    }

    private fun observation(
        parent: Context,
        span: SpanBuilder,
        ended: ((outcome: String, nanos: Long) -> Unit)? = null,
    ): Observation {
        val started: Span = span.setParent(parent).startSpan()
        return OtelObservation(started, OtelTraceContext(parent.with(started), propagator), ended)
    }
}

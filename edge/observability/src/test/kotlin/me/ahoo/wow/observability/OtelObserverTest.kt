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

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.TraceContext
import me.ahoo.wow.spi.observation.observe
import me.ahoo.wow.spi.observation.trace
import me.ahoo.wow.spi.timer.Timer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

class OtelObserverTest {
    private val telemetry = Telemetry().apply { now = 10_000 }
    private val observer = telemetry.observer
    private val ref = ORDER.ref("t", "o-1")
    private val command = Command("c-1", "r-1", ref, PlaceOrder(1))
    private val stream = EventStreamRecord(
        ref = ref,
        version = 2,
        commandId = "c-1",
        requestId = "r-1",
        commandType = "$ORDER.PlaceOrder",
        commitTime = 9_000,
        events = listOf(EventRecord("$ORDER.OrderPlaced", 1, "{}")),
        scope = Scope.NONE,
    )

    /** The propagation fields of a span of another service, as a request or a message carries them. */
    private fun remote(): Pair<Span, Map<String, String>> {
        val span = telemetry.sdk.getTracer("remote").spanBuilder("remote").startSpan()
        val fields = HashMap<String, String>()
        telemetry.sdk.propagators.textMapPropagator.inject(Context.root().with(span), fields, MapSetter)
        span.end()
        return span to fields
    }

    @Test
    fun `an admission should be a server span, the child of the request's trace`(): Unit = runBlocking {
        val (caller, fields) = remote()
        val observed = observer.admitting(SALES, "order", "PlaceOrder", fields)
        observed.observe { observed.outcome("admitted") }
        val admit = telemetry.single("admit $SALES.order")
        admit.kind.assert().isEqualTo(SpanKind.SERVER)
        admit.traceId.assert().isEqualTo(caller.spanContext.traceId)
        admit.parentSpanId.assert().isEqualTo(caller.spanContext.spanId)
        admit.attr(WowAttributes.COMMAND).assert().isEqualTo("$SALES.order.PlaceOrder")
        admit.attr(WowAttributes.OUTCOME).assert().isEqualTo("admitted")
    }

    @Test
    fun `a stage without propagated fields should be the child of the current span`(): Unit = runBlocking {
        val parent = telemetry.sdk.getTracer("app").spanBuilder("app").startSpan()
        parent.makeCurrent().use { observer.admitting(SALES, "order", "PlaceOrder", emptyMap()).end() }
        parent.end()
        telemetry.single("admit $SALES.order").parentSpanId.assert().isEqualTo(parent.spanContext.spanId)
    }

    @Test
    fun `an execution should time the commit by its outcome, and its decision and append be its children`(): Unit =
        runBlocking {
            val executing = observer.executing(command, PLACE_ORDER)
            executing.observe {
                observer.deciding(command).observe { }
                val appending = observer.appending(stream)
                appending.observe { appending.trace["traceparent"].assert().isNotNull() }
                executing.outcome("committed")
            }
            observer.executing(command, PLACE_ORDER).observe { }
            val execute = telemetry.named("execute $ORDER").first()
            execute.attr(WowAttributes.COMMAND_ID).assert().isEqualTo("c-1")
            execute.attr(WowAttributes.COMMAND).assert().isEqualTo("$ORDER.PlaceOrder")
            execute.attr(WowAttributes.REQUEST_ID).assert().isEqualTo("r-1")
            execute.attr(WowAttributes.AGGREGATE_ID).assert().isEqualTo("o-1")
            telemetry.single("decide $ORDER").parentSpanId.assert().isEqualTo(execute.spanId)
            telemetry.single("append $ORDER").parentSpanId.assert().isEqualTo(execute.spanId)
            val committed = telemetry.meters.find(WowMetrics.COMMAND_DURATION)
                .tags(
                    WowMetrics.CONTEXT,
                    SALES,
                    WowMetrics.AGGREGATE,
                    "order",
                    WowMetrics.OUTCOME,
                    "committed"
                ).timer()!!
            committed.count().assert().isEqualTo(1L)
            // An execution that names no outcome ends `ok`.
            telemetry.meters.find(WowMetrics.COMMAND_DURATION).tags(WowMetrics.OUTCOME, OtelObservation.OK).timer()!!
                .count().assert().isEqualTo(1L)
        }

    @Test
    fun `the execution span should carry the kernel's resolved command type, as the admission does`(): Unit =
        runBlocking {
            observer.executing(command, "$ORDER.Place").observe { }
            observer.admitting(SALES, "order", "$ORDER.Place", emptyMap()).end()
            telemetry.single("execute $ORDER").attr(WowAttributes.COMMAND).assert().isEqualTo("$ORDER.Place")
            telemetry.single("admit $SALES.order").attr(WowAttributes.COMMAND).assert().isEqualTo("$ORDER.Place")
        }

    @Test
    fun `refusals, wait timeouts, dropped signals, failed forwards, tail reads and store fallbacks should be counted`() {
        observer.observed(ObservedEvent.Refused(ORDER, "wow.command.busy"))
        observer.observed(ObservedEvent.Refused(ORDER, "wow.command.busy"))
        observer.observed(ObservedEvent.WaitTimedOut(ORDER, Stage.COMMITTED))
        observer.observed(ObservedEvent.SignalDropped("node-2", ObservedEvent.SignalDropped.Reason.QUEUE_FULL))
        observer.observed(ObservedEvent.ForwardFailed(ORDER, ObservedEvent.ForwardFailed.Outcome.UNKNOWN))
        observer.observed(ObservedEvent.TailRead(SALES, 3, ObservedEvent.TailRead.Reason.GAP))
        observer.observed(ObservedEvent.StateCache(SALES, ObservedEvent.StateCache.Outcome.MISS))
        observer.observed(ObservedEvent.StateCache(SALES, ObservedEvent.StateCache.Outcome.EVICTED))
        observer.observed(ObservedEvent.ReservationLost(SALES))
        observer.observed(
            ObservedEvent.StoreFellBack(
                SALES,
                ObservedEvent.StoreFellBack.Capability.SNAPSHOT_TAIL_READ,
                ObservedEvent.StoreFellBack.Reason.FAILED
            )
        )
        val meters = telemetry.meters
        meters.get(WowMetrics.STORE_FALLBACKS).tags(
            WowMetrics.CONTEXT,
            SALES,
            WowMetrics.CAPABILITY,
            "snapshot-tail-read",
            WowMetrics.REASON,
            "failed",
        ).counter().count().assert().isEqualTo(1.0)
        meters.get(WowMetrics.RESERVATION_LOSSES)
            .tags(WowMetrics.CONTEXT, SALES).counter().count().assert().isEqualTo(1.0)
        meters.get(WowMetrics.STATE_CACHE_LOOKUPS).tags(WowMetrics.CONTEXT, SALES, WowMetrics.OUTCOME, "miss")
            .counter().count().assert().isEqualTo(1.0)
        // An eviction is not a lookup: it counts on its own.
        meters.get(WowMetrics.STATE_CACHE_EVICTIONS).tags(WowMetrics.CONTEXT, SALES).counter().count()
            .assert().isEqualTo(1.0)
        meters.find(WowMetrics.STATE_CACHE_LOOKUPS).tags(WowMetrics.OUTCOME, "evicted").counter().assert().isNull()
        meters.get(WowMetrics.TAIL_READS).tags(WowMetrics.CONTEXT, SALES, WowMetrics.REASON, "gap").counter().count()
            .assert().isEqualTo(1.0)
        meters.get(WowMetrics.COMMAND_REFUSALS)
            .tags(WowMetrics.CONTEXT, SALES, WowMetrics.ERROR_TYPE, "wow.command.busy")
            .counter().count().assert().isEqualTo(2.0)
        meters.get(WowMetrics.WAIT_TIMEOUTS).tags(WowMetrics.AGGREGATE, "order", WowMetrics.STAGE, "committed")
            .counter().count().assert().isEqualTo(1.0)
        meters.get(WowMetrics.SIGNAL_DROPS).tags(WowMetrics.REASON, "queue-full").counter().count()
            .assert().isEqualTo(1.0)
        meters.get(WowMetrics.FORWARD_FAILURES).tags(WowMetrics.OUTCOME, "unknown").counter().count()
            .assert().isEqualTo(1.0)
    }

    @Test
    fun `every stage and every observed event should record under the metric names a dashboard reads`(): Unit =
        runBlocking {
            observer.admitting(SALES, "order", "PlaceOrder", emptyMap()).observe { }
            observer.executing(command, PLACE_ORDER).observe { }
            observer.deciding(command).observe { }
            observer.appending(stream).observe { }
            observer.handling("Board.onOrder", SALES, stream, emptyMap(), null).observe { }
            observer.firing(
                Timer("t", ref, "expire", 2, 0, ScheduleRule.At(9_000), 9_000, ref, "$ORDER.CancelOrder", "{}"),
            ).observe { }
            listOf(
                ObservedEvent.Refused(ORDER, "wow.command.busy"),
                ObservedEvent.WaitTimedOut(ORDER, Stage.COMMITTED),
                ObservedEvent.SignalDropped("node-2", ObservedEvent.SignalDropped.Reason.QUEUE_FULL),
                ObservedEvent.ForwardFailed(ORDER, ObservedEvent.ForwardFailed.Outcome.UNKNOWN),
                ObservedEvent.StateCache(SALES, ObservedEvent.StateCache.Outcome.MISS),
                ObservedEvent.StateCache(SALES, ObservedEvent.StateCache.Outcome.EVICTED),
                ObservedEvent.ReservationLost(SALES),
                ObservedEvent.StoreFellBack(
                    SALES,
                    ObservedEvent.StoreFellBack.Capability.SNAPSHOT_TAIL_READ,
                    ObservedEvent.StoreFellBack.Reason.FAILED
                ),
                ObservedEvent.PartitionPaused("sales", "Board.onEvent", 3, "down"),
                ObservedEvent.PartitionResumed("sales", "Board.onEvent", 3, 1_500),
                ObservedEvent.PartitionThrottled("sales", "Board.onEvent", 3, true),
                ObservedEvent.Backpressured("sales", "Board.onEvent", 3, "wow.command.busy"),
                ObservedEvent.TailRead(SALES, 3, ObservedEvent.TailRead.Reason.GAP),
                ObservedEvent.Undecodable("orders", 0, 7, parked = true),
            ).forEach(observer::observed)
            // The names dashboards and alerts are built on: a change here is a breaking change for operators.
            telemetry.meters.meters.map { it.id.name }.toSortedSet().assert().containsExactly(
                "wow.command.duration",
                "wow.command.forward.failures",
                "wow.command.refusals",
                "wow.log.tail.reads",
                "wow.reservation.losses",
                "wow.state_cache.evictions",
                "wow.state_cache.lookups",
                "wow.store.fallbacks",
                "wow.subscription.backpressure.refusals",
                "wow.subscription.lag",
                "wow.subscription.paused.notices",
                "wow.subscription.throttled.notices",
                "wow.subscription.undecodable.messages",
                "wow.timer.delay",
                "wow.wait.signal.drops",
                "wow.wait.timeouts",
            )
        }

    @Test
    fun `every value of a closed set should tag its meter as it always has`() {
        ObservedEvent.SignalDropped.Reason.entries.forEach { observer.observed(ObservedEvent.SignalDropped("n", it)) }
        ObservedEvent.ForwardFailed.Outcome.entries.forEach {
            observer.observed(
                ObservedEvent.ForwardFailed(ORDER, it)
            )
        }
        ObservedEvent.StateCache.Outcome.entries.forEach { observer.observed(ObservedEvent.StateCache(SALES, it)) }
        ObservedEvent.TailRead.Reason.entries.forEach { observer.observed(ObservedEvent.TailRead(SALES, 0, it)) }
        ObservedEvent.StoreFellBack.Capability.entries.forEach { capability ->
            ObservedEvent.StoreFellBack.Reason.entries.forEach {
                observer.observed(ObservedEvent.StoreFellBack(SALES, capability, it))
            }
        }
        fun tags(meter: String, tag: String) =
            telemetry.meters.find(meter).counters().mapNotNull { it.id.getTag(tag) }.toSortedSet()
        tags(WowMetrics.SIGNAL_DROPS, WowMetrics.REASON).assert()
            .containsExactly("closed", "expired", "failed", "queue-full", "refused")
        tags(WowMetrics.FORWARD_FAILURES, WowMetrics.OUTCOME).assert().containsExactly("busy", "local", "unknown")
        tags(WowMetrics.STATE_CACHE_LOOKUPS, WowMetrics.OUTCOME).assert().containsExactly("hit", "miss")
        telemetry.meters.get(WowMetrics.STATE_CACHE_EVICTIONS).counter().count().assert().isEqualTo(1.0)
        tags(WowMetrics.TAIL_READS, WowMetrics.REASON).assert().containsExactly("gap", "idle", "overflow", "start")
        tags(WowMetrics.STORE_FALLBACKS, WowMetrics.CAPABILITY).assert()
            .containsExactly("snapshot-rebuild", "snapshot-tail-read")
        tags(WowMetrics.STORE_FALLBACKS, WowMetrics.REASON).assert().containsExactly("failed", "unsupported")
    }

    @Test
    fun `the throttles of a partition and its backpressured attempts should be counted`() {
        observer.observed(ObservedEvent.PartitionThrottled("sales", "Board.onEvent", 3, true))
        observer.observed(ObservedEvent.PartitionThrottled("sales", "Board.onEvent", 3, false))
        observer.observed(ObservedEvent.Backpressured("sales", "Board.onEvent", 3, "wow.wait.timeout"))
        observer.observed(ObservedEvent.Backpressured("sales", "Board.onEvent", 3, "wow.wait.timeout"))
        val board = arrayOf(WowMetrics.GROUP, "sales", WowMetrics.SUBSCRIPTION, "Board.onEvent")
        listOf(WowMetrics.THROTTLED_KIND, WowMetrics.UNTHROTTLED_KIND).forEach {
            telemetry.meters.get(WowMetrics.THROTTLED_NOTICES)
                .tags(*board, WowMetrics.KIND, it)
                .counter().count().assert().isEqualTo(1.0)
        }
        telemetry.meters.get(WowMetrics.BACKPRESSURE_REFUSALS)
            .tags(*board, WowMetrics.ERROR_TYPE, "wow.wait.timeout").counter().count()
            .assert().isEqualTo(2.0)
    }

    @Test
    fun `the pauses of a partition and its resumes should be counted`() {
        observer.observed(ObservedEvent.PartitionPaused("sales", "Board.onEvent", 3, "a systemic failure"))
        observer.observed(ObservedEvent.PartitionResumed("sales", "Board.onEvent", 3, 1_500))
        observer.observed(ObservedEvent.PartitionPaused("sales", "Board.onEvent", 3, "a systemic failure"))
        val board = arrayOf(
            WowMetrics.GROUP,
            "sales",
            WowMetrics.SUBSCRIPTION,
            "Board.onEvent",
        )
        telemetry.meters.get(WowMetrics.PAUSED_NOTICES).tags(*board, WowMetrics.KIND, WowMetrics.PAUSED_KIND)
            .counter().count().assert().isEqualTo(2.0)
        telemetry.meters.get(WowMetrics.PAUSED_NOTICES).tags(*board, WowMetrics.KIND, WowMetrics.RESUMED_KIND)
            .counter().count().assert().isEqualTo(1.0)
    }

    @Test
    fun `a failure should be recorded on the span, a cancellation only named`(): Unit = runBlocking {
        assertThrows<IllegalStateException> {
            observer.executing(command, PLACE_ORDER).observe { error("The store is down.") }
        }
        assertThrows<CancellationException> {
            observer.executing(command, PLACE_ORDER).observe { throw CancellationException("The caller left.") }
        }
        val (failed, cancelled) = telemetry.named("execute $ORDER")
        failed.status.statusCode.assert().isEqualTo(StatusCode.ERROR)
        failed.status.description.assert().isEqualTo("The store is down.")
        failed.events.map { it.name }.assert().containsExactly("exception")
        cancelled.status.statusCode.assert().isEqualTo(StatusCode.UNSET)
        cancelled.attr(WowAttributes.OUTCOME).assert().isEqualTo(OtelObservation.CANCELLED)
        listOf(OtelObservation.ERROR, OtelObservation.CANCELLED).forEach { outcome ->
            telemetry.meters.find(WowMetrics.COMMAND_DURATION).tags(WowMetrics.OUTCOME, outcome).timer()!!
                .count().assert().isEqualTo(1L)
        }
    }

    @Test
    fun `a handling of the log should continue the entry's trace and time the subscription's lag`(): Unit =
        runBlocking {
            val (append, fields) = remote()
            observer.handling("Board.onOrder", SALES, stream, fields, null).observe { }
            val handle = telemetry.single("handle Board.onOrder")
            handle.kind.assert().isEqualTo(SpanKind.INTERNAL)
            handle.parentSpanId.assert().isEqualTo(append.spanContext.spanId)
            handle.attr(WowAttributes.SUBSCRIPTION).assert().isEqualTo("Board.onOrder")
            val lag = telemetry.meters.find(WowMetrics.SUBSCRIPTION_LAG)
                .tags(WowMetrics.CONTEXT, SALES, WowMetrics.SUBSCRIPTION, "Board.onOrder").timer()!!
            lag.count().assert().isEqualTo(1L)
            lag.totalTime(TimeUnit.MILLISECONDS).assert().isEqualTo(1_000.0)
        }

    @Test
    fun `a handling of a topic should be a consumer span, and a message without a commit time lag nothing`(): Unit =
        runBlocking {
            observer.handling("wow.commands", STOCK, stream.copyWith(commitTime = 0), emptyMap(), "wow.stock.commands")
                .observe { }
            val process = telemetry.single("wow.stock.commands process")
            process.kind.assert().isEqualTo(SpanKind.CONSUMER)
            process.attr(WowAttributes.MESSAGING_DESTINATION).assert().isEqualTo("wow.stock.commands")
            process.attr(WowAttributes.MESSAGING_OPERATION).assert().isEqualTo("process")
            telemetry.meters.find(WowMetrics.SUBSCRIPTION_LAG).timer().assert().isNull()
        }

    @Test
    fun `a timer's firing should be a span, its delay past the due time timed`(): Unit = runBlocking {
        fun timer(dueAt: Long) = Timer(
            id = "$ref#expire@2",
            owner = ref,
            key = "expire",
            version = 2,
            partition = 0,
            rule = ScheduleRule.At(dueAt),
            dueAt = dueAt,
            target = ref,
            commandType = "$ORDER.CancelOrder",
            body = "{}",
        )
        observer.firing(timer(9_750)).observe { }
        // A timer fired before its due time, by this node's clock, is not late.
        observer.firing(timer(20_000)).observe { }
        val fire = telemetry.named("fire $ORDER.CancelOrder").first()
        fire.attr(WowAttributes.TIMER_ID).assert().isEqualTo("$ref#expire@2")
        val delay = telemetry.meters.find(WowMetrics.TIMER_DELAY).tags(WowMetrics.CONTEXT, SALES).timer()!!
        delay.count().assert().isEqualTo(2L)
        delay.totalTime(TimeUnit.MILLISECONDS).assert().isEqualTo(250.0)
    }

    @Test
    fun `a trace resumed from its fields should make its span current, and nothing should be resumed without`(): Unit =
        runBlocking {
            observer.resuming(emptyMap()).assert().isSameAs(EmptyCoroutineContext)
            val (forwarder, fields) = remote()
            val resumed = observer.resuming(fields) as OtelTraceContext
            withContext(resumed) {
                Span.current().spanContext.spanId.assert().isEqualTo(forwarder.spanContext.spanId)
                currentCoroutineContext().trace.assert().isEqualTo(fields)
                currentCoroutineContext()[TraceContext].assert().isSameAs(resumed)
            }
            Span.current().spanContext.isValid.assert().isFalse()
            resumed.toString().assert().startsWith("OtelTraceContext(")
        }

    private companion object {
        /** The kernel's resolved type name of [PlaceOrder]. */
        val PLACE_ORDER = "$ORDER.PlaceOrder"
    }
}

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

package me.ahoo.wow.opentelemetry

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
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
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
        admit.attr(WowAttributes.COMMAND).assert().isEqualTo("PlaceOrder")
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
            val executing = observer.executing(command)
            executing.observe {
                observer.deciding(command).observe { }
                val appending = observer.appending(stream)
                appending.observe { appending.trace["traceparent"].assert().isNotNull() }
                executing.outcome("committed")
            }
            observer.executing(command).observe { }
            val execute = telemetry.named("execute $ORDER").first()
            execute.attr(WowAttributes.COMMAND_ID).assert().isEqualTo("c-1")
            execute.attr(WowAttributes.REQUEST_ID).assert().isEqualTo("r-1")
            execute.attr(WowAttributes.AGGREGATE_ID).assert().isEqualTo("o-1")
            telemetry.single("decide $ORDER").parentSpanId.assert().isEqualTo(execute.spanId)
            telemetry.single("append $ORDER").parentSpanId.assert().isEqualTo(execute.spanId)
            val committed = telemetry.meters.find(WowMetrics.COMMIT)
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
            telemetry.meters.find(WowMetrics.COMMIT).tags(WowMetrics.OUTCOME, OtelObservation.OK).timer()!!
                .count().assert().isEqualTo(1L)
        }

    @Test
    fun `a failure should be recorded on the span, a cancellation only named`(): Unit = runBlocking {
        assertThrows<IllegalStateException> {
            observer.executing(command).observe { error("The store is down.") }
        }
        assertThrows<CancellationException> {
            observer.executing(command).observe { throw CancellationException("The caller left.") }
        }
        val (failed, cancelled) = telemetry.named("execute $ORDER")
        failed.status.statusCode.assert().isEqualTo(StatusCode.ERROR)
        failed.status.description.assert().isEqualTo("The store is down.")
        failed.events.map { it.name }.assert().containsExactly("exception")
        cancelled.status.statusCode.assert().isEqualTo(StatusCode.UNSET)
        cancelled.attr(WowAttributes.OUTCOME).assert().isEqualTo(OtelObservation.CANCELLED)
        listOf(OtelObservation.ERROR, OtelObservation.CANCELLED).forEach { outcome ->
            telemetry.meters.find(WowMetrics.COMMIT).tags(WowMetrics.OUTCOME, outcome).timer()!!
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
}

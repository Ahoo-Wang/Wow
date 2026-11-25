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

import io.opentelemetry.sdk.trace.data.SpanData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.KernelOptions
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.LruStateCache
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.observation.observe
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The traces of commands an aggregate's lane commits as one batch, through the real OpenTelemetry observer: three
 * requests for one stock item, the first deciding on I/O while the other two queue behind it, which then commit
 * together (#6855, and #6860's suspicion that a batch's commands run in its first command's trace).
 */
class BatchTraceTest {
    data class Hold(val gate: String)

    private val telemetry = Telemetry()
    private val gate = CompletableDeferred<Unit>()
    private val holding = CompletableDeferred<Unit>()

    /** The stock item, whose [Hold] decides on I/O, so it runs alone, and whose reservations are pure. */
    private val model = aggregate<ItemState>(STOCK, "item") {
        initial { ItemState() }
        decideSuspending<Hold>(Creation.ALLOWED) { _, _ ->
            holding.complete(Unit)
            gate.await()
            Decision.events(Reserved(0))
        }
        decide<Reserve>(Creation.ALLOWED) { _, body ->
            if (body.quantity > AVAILABLE) {
                Decision.reject(INSUFFICIENT, "Only $AVAILABLE left.")
            } else {
                Decision.events(Reserved(body.quantity))
            }
        }
        evolve<Reserved> { reserved += it.quantity }
    }

    /** Records the size of each appendAll. */
    private class BatchLog(private val delegate: EventLog = InMemoryEventLog()) : EventLog by delegate {
        val batches = CopyOnWriteArrayList<Int>()

        override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
            batches += streams.size
            return delegate.appendAll(streams)
        }
    }

    private val log = BatchLog()
    private val executor = Executor(
        Kernel(
            log,
            states = LruStateCache(capacity = 16),
            options = KernelOptions(recentRequests = 32),
            observer = telemetry.observer,
        ),
        maxBatch = 4,
    )
    private val ref = ITEM.ref("t", "i-1")

    /** [body] executed as an admitted request does, in its admission's span, a root of its own trace. */
    private suspend fun admitted(id: String, body: Any): CommandOutcome {
        val admission = telemetry.observer.admitting(STOCK, "item", body.javaClass.simpleName, emptyMap())
        return admission.observe { executor.execute(model, Command(id, id, ref, body)) }
    }

    @Test
    fun `each command a batch commits should be traced as its own request's, its execution timed`(): Unit =
        runBlocking {
            val held = async(Dispatchers.Default) { admitted("held", Hold("g")) }
            withTimeout(TIMEOUT_MS) { holding.await() }
            val reserves = (1..2).map { n ->
                async(Dispatchers.Default) { admitted("reserve-$n", Reserve(n)) }
                    .also { withTimeout(TIMEOUT_MS) { while (executor.commandsInFlight < n + 1) yield() } }
            }
            gate.complete(Unit)
            held.await()
            reserves.awaitAll().assert().allMatch { it is CommandOutcome.Committed }
            log.batches.assert().containsExactly(2)

            val admits = telemetry.named("admit $ITEM")
            val executions = telemetry.named("execute $ITEM")
            executions.map { it.attr(WowAttributes.COMMAND_ID) }.assert()
                .containsExactlyInAnyOrder("held", "reserve-1", "reserve-2")
            // Each execution is the child of its own admission, in its trace, its decision and append its children.
            admits.map { it.traceId }.distinct().assert().hasSize(3)
            executions.forEach { execute ->
                val admit = admits.single { it.spanId == execute.parentSpanId }
                execute.traceId.assert().isEqualTo(admit.traceId)
                telemetry.childrenOf(execute).map { it.name }.assert()
                    .containsExactlyInAnyOrder("decide $ITEM", "append $ITEM")
            }
            // What each stream carries for delivery continues its own command's trace, from its append.
            log.readEntries(ref).toList().forEach { entry ->
                val execute = executions.single { it.attr(WowAttributes.COMMAND_ID) == entry.stream.commandId }
                val append = telemetry.childrenOf(execute).single { it.name == "append $ITEM" }
                entry.decodedTransport!!.trace.getValue("traceparent").assert()
                    .startsWith("00-${append.traceId}-${append.spanId}-")
            }
            telemetry.meters.find(WowMetrics.COMMAND_DURATION.name)
                .tags(WowMetrics.CONTEXT, STOCK, WowMetrics.OUTCOME, "committed")
                .timer()!!.count().assert().isEqualTo(3L)
        }

    @Test
    fun `a batched command rejected without events should be counted and timed once`(): Unit = runBlocking {
        val held = async(Dispatchers.Default) { admitted("held", Hold("g")) }
        withTimeout(TIMEOUT_MS) { holding.await() }
        // The second is rejected: the batch hands it back, and it is decided again alone.
        val reserves = listOf(1, AVAILABLE + 1).mapIndexed { at, quantity ->
            async(Dispatchers.Default) { admitted("reserve-${at + 1}", Reserve(quantity)) }
                .also { withTimeout(TIMEOUT_MS) { while (executor.commandsInFlight < at + 2) yield() } }
        }
        gate.complete(Unit)
        held.await()
        val (committed, rejected) = reserves.awaitAll()
        committed.assert().isInstanceOf(CommandOutcome.Committed::class.java)
        (rejected as CommandOutcome.Rejected).rejection.code.assert().isEqualTo(INSUFFICIENT)

        telemetry.meters.find(WowMetrics.COMMAND_REJECTIONS.name).tags(WowMetrics.CONTEXT, STOCK).counters()
            .sumOf { it.count() }.assert().isEqualTo(1.0)
        telemetry.meters.find(WowMetrics.COMMAND_DURATION.name)
            .tags(WowMetrics.CONTEXT, STOCK, WowMetrics.OUTCOME, "rejected")
            .timer()!!.count().assert().isEqualTo(1L)
        telemetry.named("execute $ITEM").filter { it.attr(WowAttributes.COMMAND_ID) == "reserve-2" }
            .map { it.attr(WowAttributes.OUTCOME) }.assert().containsExactly("rejected")
    }

    private fun Telemetry.childrenOf(span: SpanData): List<SpanData> =
        spans.finishedSpanItems.filter { it.parentSpanId == span.spanId }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}

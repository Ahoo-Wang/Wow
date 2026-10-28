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

package me.ahoo.wow.runtime.sink

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.ApplyTags
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.TestTrace
import me.ahoo.wow.runtime.state.StateViewReader
import me.ahoo.wow.runtime.state.StateViews
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The records a sink subscription writes (design 04 §4, decisions D58, D133, D140). */
class SinkSubscriptionTest {
    data class SetPrice(val amount: BigDecimal)

    /** Revision 2: revision 1 was `{"price": …}`, stored as `shop.order.PriceSet`. */
    data class Priced(val amount: BigDecimal, val currency: String)

    class PricedOrder {
        var amount: BigDecimal = BigDecimal.ZERO
        var currency: String = ""
    }

    private val type = AggregateType("shop", "order")
    private val ref = type.ref("t", "o-1")
    private val model: AggregateModel<PricedOrder> = aggregate<PricedOrder>("shop", "order") {
        initial { PricedOrder() }
        decide<SetPrice>(Creation.ALLOWED) { _, body -> Decision.events(Priced(body.amount, "EUR")) }
        evolve<Priced>(revision = 2) {
            amount = it.amount
            currency = it.currency
        }
        upgrade<Priced>(1) { node ->
            (node as ObjectNode).deepCopy().also {
                it.set("amount", node["price"])
                it.remove("price")
                it.put("currency", "EUR")
            }
        }
        alias<Priced>("shop.order.PriceSet")
        retired(DISCOUNTED)
    }

    private val codec = EventCodec()
    private val log = InMemoryEventLog(1)
    private val kernel = Kernel(log, codec = codec)
    private val views = StateViews(codec, mapOf(NAME to setOf(type)))
    private val executor = Executor(kernel, views = views)
    private val written = CopyOnWriteArrayList<StateEventRecord>()
    private val commands = AtomicInteger()

    private fun sink(includeState: Boolean = true) = SinkSubscription(
        NAME,
        type,
        { written += it },
        StateViewReader(NAME, listOf(model), kernel, codec, views),
        includeState,
    )

    private suspend fun commit(body: Any, scope: Scope = Scope.NONE): EventStreamRecord {
        val id = commands.incrementAndGet()
        val command = Command("c-$id", "r-$id", ref, body, scope = scope)
        return (executor.execute(model, command) as CommandOutcome.Committed).stream
    }

    private suspend fun handle(sink: SinkSubscription, stream: EventStreamRecord, transport: Transport? = null) {
        val position = log.positionOf(stream.ref, stream.version)
        sink.subscription.handle(sink.subscription.decoder.decode(stream, position, transport))
    }

    private fun tree(json: String) = WowJson.mapper.readTree(json)

    @Test
    fun `a commit should be written with its facts, current events and the state after it`(): Unit = runBlocking {
        val sink = sink()
        val stream = commit(SetPrice(BigDecimal("2.50")), Scope(ownerId = "alice"))
        handle(sink, stream)
        val record = written.single()
        record.id.assert().isEqualTo("shop.order/t/o-1@1")
        record.commandId.assert().isEqualTo(stream.commandId)
        record.requestId.assert().isEqualTo(stream.requestId)
        record.commandType.assert().isEqualTo("shop.order.SetPrice")
        record.commitTime.assert().isEqualTo(stream.commitTime)
        record.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        record.events.single().name.assert().isEqualTo("shop.order.Priced")
        record.events.single().revision.assert().isEqualTo(2)
        record.events.single().payload.assert().isEqualTo(tree("""{"amount":2.50,"currency":"EUR"}"""))
        record.state!!.version.assert().isEqualTo(1)
        record.state!!.schemaFingerprint.assert().isEqualTo(codec.stateSchemaFingerprint(model))
        record.state!!.state.assert().isEqualTo(tree("""{"amount":2.50,"currency":"EUR"}"""))
        record.deleted.assert().isFalse()
        record.transport.assert().isNull()
        sink.toString().assert().isEqualTo("SinkSubscription($NAME)")
    }

    @Test
    fun `an event of an older revision under a former name should be written upgraded`(): Unit = runBlocking {
        val stream = EventStreamRecord(
            ref = ref,
            version = 1,
            commandId = "c-1",
            requestId = "r-1",
            commandType = "shop.order.SetPrice",
            commitTime = 7L,
            events = listOf(EventRecord("shop.order.PriceSet", 1, """{"price":3.10}""")),
            scope = Scope.NONE,
            causation = Causation("root", "shop.cart/t/alice@2"),
            headers = mapOf("app" to "web"),
        )
        log.append(stream).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        handle(sink(), log.read(ref).first())
        val record = written.single()
        record.events.single().name.assert().isEqualTo("shop.order.Priced")
        record.events.single().revision.assert().isEqualTo(2)
        record.events.single().payload.assert().isEqualTo(tree("""{"amount":3.10,"currency":"EUR"}"""))
        // No view was taken: the state is the log replayed.
        record.state!!.state.assert().isEqualTo(tree("""{"amount":3.10,"currency":"EUR"}"""))
        record.causation.assert().isEqualTo(Causation("root", "shop.cart/t/alice@2"))
        record.headers.assert().isEqualTo(mapOf("app" to "web"))
    }

    @Test
    fun `a retired event should be left out, and a stream of only retired events not written`(): Unit = runBlocking {
        val discounted = EventRecord(DISCOUNTED, 1, """{"percent":10}""")
        fun stream(version: Int, vararg events: EventRecord) = EventStreamRecord(
            ref = ref,
            version = version,
            commandId = "c-$version",
            requestId = "r-$version",
            commandType = "shop.order.SetPrice",
            commitTime = version.toLong(),
            events = events.toList(),
            scope = Scope.NONE,
        )
        val priced = EventRecord("shop.order.Priced", 2, """{"amount":1.20,"currency":"EUR"}""")
        log.append(stream(1, discounted, priced)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.append(stream(2, discounted)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        val sink = sink()
        log.read(ref).collect { handle(sink, it) }
        // Decision D219: version 1 without its retired event; version 2, all retired, is passed over.
        val record = written.single()
        record.version.assert().isEqualTo(1)
        record.events.map { it.name }.assert().containsExactly("shop.order.Priced")
        record.state!!.state.assert().isEqualTo(tree("""{"amount":1.20,"currency":"EUR"}"""))
    }

    @Test
    fun `a stream published without its state should still carry the deleted mark and tags`(): Unit = runBlocking {
        val sink = sink(includeState = false)
        val tags = mapOf("tier" to listOf("gold"))
        handle(sink, commit(SetPrice(BigDecimal.ONE)))
        handle(sink, commit(ApplyTags(tags)))
        handle(sink, commit(DeleteAggregate))
        written.map { it.state }.assert().containsOnlyNulls()
        written.map { it.version }.assert().containsExactly(1, 2, 3)
        written[1].tags.assert().isEqualTo(tags)
        written[2].deleted.assert().isTrue()
        written[2].events.single().name.assert().isEqualTo("wow.lifecycle.AggregateDeleted")
    }

    @Test
    fun `the wait context and the trace should go with the record, the reservations stay`(): Unit = runBlocking {
        val sink = sink()
        val wait = WaitContext("w", Origin("n-1", "n-1:9090", "shop"), listOf(Hop.handled("OrderView.onEvent")), 9L)
        val reservations = listOf(ReservationKey("email", "a@b"))
        handle(sink, commit(SetPrice(BigDecimal.ONE)), Transport(wait, reservations, mapOf("traceparent" to "t")))
        handle(sink, commit(SetPrice(BigDecimal.TEN)), Transport(reservations = reservations))
        written[0].transport.assert().isEqualTo(Transport(wait, trace = mapOf("traceparent" to "t")))
        written[1].transport.assert().isNull()
    }

    @Test
    fun `an observed sink should write the trace of its own handling instead of the entry's`(): Unit = runBlocking {
        val sink = sink()
        val wait = WaitContext("w", Origin("n-1", "n-1:9090", "shop"), listOf(Hop.handled("OrderView.onEvent")), 9L)
        withContext(TestTrace("sink")) {
            handle(sink, commit(SetPrice(BigDecimal.ONE)), Transport(wait, trace = mapOf("traceparent" to "t")))
            handle(sink, commit(SetPrice(BigDecimal.TEN)))
        }
        written[0].transport.assert().isEqualTo(Transport(wait, trace = mapOf("traceparent" to "sink")))
        written[1].transport.assert().isEqualTo(Transport(trace = mapOf("traceparent" to "sink")))
    }

    @Test
    fun `a sink should read the state views under its own name`() {
        assertThrows<IllegalArgumentException> {
            SinkSubscription(NAME, type, {}, StateViewReader("other", listOf(model), kernel, codec, views))
        }
    }

    private companion object {
        const val NAME = "transport-sink.order"
        const val DISCOUNTED = "shop.order.Discounted"
    }
}

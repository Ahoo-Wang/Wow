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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.state.SharedStateReader
import me.ahoo.wow.runtime.state.SharedStates
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.state.StateEventRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What an older node's sink does with a newer node's commit during a rolling update (D485 ⑩): an event of a name its
 * model does not know, or of its own name at a revision newer than its model's, fails its entry before any mask of
 * the event is taken, so it is neither written as stored nor masked by the older schema, which cannot tell what a
 * newer revision added; the subscription retries it and parks the aggregate, as any entry it cannot read.
 */
class SinkNewerEventsTest {
    data class SetPrice(val amount: BigDecimal)

    data class Priced(val amount: BigDecimal, val currency: String)

    class PricedOrder {
        var amount: BigDecimal = BigDecimal.ZERO
    }

    private val type = AggregateType("shop", "order")
    private val model: AggregateModel<PricedOrder> = aggregate<PricedOrder>("shop", "order") {
        initial { PricedOrder() }
        decide<SetPrice>(Creation.ALLOWED) { _, body -> Decision.events(Priced(body.amount, "EUR")) }
        evolve<Priced>(revision = 2) { amount = it.amount }
        upgrade<Priced>(1) { it }
    }

    private val codec = EventCodec()
    private val log = InMemoryEventLog(1)
    private val kernel = Kernel(log, codec = codec)
    private val views = SharedStates(codec, mapOf(NAME to setOf(type)))
    private val executor = Executor(kernel, views = views)
    private val written = CopyOnWriteArrayList<StateEventRecord>()

    /** The node's masks: `currency` of a state and of the bodies of `shop.order.Priced` masked, as its schema says. */
    private val masks = FieldMasks("currency", setOf("shop.order.Priced"))
    private val sink = SinkSubscription(
        NAME,
        type,
        { written += it },
        SharedStateReader(NAME, listOf(model), kernel, codec, views),
        masks = RecordMasks(masks, type, revealsDisplay = false),
    )

    /** The entry of a commit of this node, its events replaced by [events], as a newer node would have written them. */
    private suspend fun entryOf(vararg events: EventRecord): SubscribedEntry {
        val command = Command("c-1", "r-1", type.ref("t", "o-1"), SetPrice(BigDecimal.ONE))
        val stream = (executor.execute(model, command) as CommandOutcome.Committed).stream
        val position = log.positionOf(stream.ref, stream.version)
        return sink.subscription.decoder.decode(stream, position, null)
            .copy(stream = stream.copyWith(events = events.toList()))
    }

    @Test
    fun `an event of a revision newer than the model's fails its entry, never masked by the older schema`(): Unit =
        runBlocking {
            val entry = entryOf(
                EventRecord("shop.order.Priced", 3, """{"amount":1,"currency":"EUR","card":"$SECRET"}"""),
            )
            val failure = assertThrows<IllegalStateException> { runBlocking { sink.subscription.handle(entry) } }
            failure.message.assert()
                .isEqualTo("shop.order.Priced revision 3 is newer than revision 2 this model reads.")
            written.assert().isEmpty()
            // The state's mask is taken with the sink; no event's mask was asked for.
            masks.asked.assert().containsExactly("state shop.order revealsDisplay=false")
        }

    @Test
    fun `an event of a name the model does not know fails its entry, never masked nor written`(): Unit = runBlocking {
        val entry = entryOf(EventRecord("shop.order.Frozen", 1, """{"card":"$SECRET"}"""))
        assertThrows<IllegalArgumentException> { runBlocking { sink.subscription.handle(entry) } }
        written.assert().isEmpty()
        masks.asked.assert().containsExactly("state shop.order revealsDisplay=false")
    }

    private companion object {
        const val NAME = "wow.transport-sink.order"
        const val SECRET = "s3cr3t-6222021234567890"
    }
}

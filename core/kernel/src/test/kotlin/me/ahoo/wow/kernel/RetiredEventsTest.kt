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

package me.ahoo.wow.kernel

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

/** Retired event types (decision D219, 9.x `DroppedEvent`): their records are skipped, never decoded. */
class RetiredEventsTest {
    private val model = aggregate<CartState>("example", "cart") {
        initial { CartState(it.id) }
        decide<CreateCart>(Creation.REQUIRED) { _, command -> Decision.events(CartCreated(command.owner)) }
        decide<AddItem> { _, command -> Decision.events(ItemAdded(command.productId, command.quantity)) }
        evolve<CartCreated> { owner = it.owner }
        evolve<ItemAdded> { items[it.productId] = (items[it.productId] ?: 0) + it.quantity }
        retired(REMOVED, "example.cart.CartArchived")
    }
    private val codec = EventCodec()
    private val log = InMemoryEventLog()
    private val kernel = Kernel(log, codec, clock = { 0L })
    private val ref = model.type.ref("tenant", "cart-1")

    private fun created() = codec.encode(model, CartCreated("ahoo"))
    private fun added(productId: String, quantity: Int) = codec.encode(model, ItemAdded(productId, quantity))
    private fun removed() = EventRecord(REMOVED, 1, """{"productId":"apple"}""")

    private suspend fun append(version: Int, vararg events: EventRecord) {
        val stream = EventStreamRecord(
            ref = ref,
            version = version,
            commandId = "c-$version",
            requestId = "r-$version",
            commandType = "example.cart.Any",
            commitTime = version.toLong(),
            events = events.toList(),
            scope = Scope.NONE,
        )
        (log.append(stream) is AppendOutcome.Committed).assert().isTrue()
    }

    /** Version 1 creates the cart, version 2 holds a retired event between two others, version 3 only a retired one. */
    private suspend fun history() {
        append(1, created())
        append(2, added("apple", 1), removed(), added("pear", 2))
        append(3, removed())
    }

    @Test
    fun `the model lists its retired events`() {
        model.retiredEvents.assert().containsExactly(REMOVED, "example.cart.CartArchived")
        model.isRetired(REMOVED).assert().isTrue()
        model.isRetired("example.cart.ItemAdded").assert().isFalse()
        model.findEventType(REMOVED).assert().isNull()
        cartModel.retiredEvents.assert().isEmpty()
    }

    @Test
    fun `a retired record is skipped, never decoded`() {
        codec.decodeUnlessRetired(model, removed()).assert().isNull()
        codec.decodeUnlessRetired(model, added("apple", 1)).assert().isEqualTo(ItemAdded("apple", 1))
        codec.decodeEvents(model, listOf(created(), removed(), added("apple", 1)))
            .assert().containsExactly(CartCreated("ahoo"), ItemAdded("apple", 1))
        // Read on its own, it fails and says it is retired.
        assertThrownBy<IllegalArgumentException> { codec.decode(model, removed()) }
            .hasMessageContaining("retired")
    }

    @Test
    fun `an unknown name that is not retired still fails`() {
        assertThrownBy<IllegalArgumentException> {
            codec.decodeEvents(model, listOf(EventRecord("example.cart.Unknown", 1, "{}")))
        }.hasMessageContaining("has no event named example.cart.Unknown")
    }

    @Test
    fun `a stream with retired events loads, its version counting`(): Unit = runBlocking {
        history()
        val loaded = kernel.stateAt(model, ref, 3)!!
        loaded.version.assert().isEqualTo(3)
        (loaded.state as CartState).items.assert().isEqualTo(mapOf("apple" to 1, "pear" to 2))
        val outcome = kernel.execute(model, Command("c-4", "r-4", ref, AddItem("apple", 2)))
        outcome as CommandOutcome.Committed
        outcome.stream.version.assert().isEqualTo(4)
        (outcome.state as CartState).items.assert().isEqualTo(mapOf("apple" to 3, "pear" to 2))
    }

    @Test
    fun `a trace leaves the retired events out`(): Unit = runBlocking {
        history()
        val trace = kernel.trace(model, ref, 1, 3, 10)
        trace.map { it.version }.assert().containsExactly(1, 2, 3)
        trace[1].events.assert().containsExactly(ItemAdded("apple", 1), ItemAdded("pear", 2))
        trace[2].events.assert().isEmpty()
    }

    @Test
    fun `the test DSL gives a history holding retired events`() = aggregateSpec(model) {
        given(CartCreated("ahoo"))
        givenRecords(added("apple", 1), removed())
        givenRecords(removed())
        whenCommand(AddItem("apple", 2), expectedVersion = 3) {
            expectEvents(ItemAdded("apple", 2))
            expectState { items.assert().isEqualTo(mapOf("apple" to 3)) }
        }
    }

    @Test
    fun `a retired name cannot be one the model still uses`() {
        fun retiring(vararg names: String, extra: AggregateModelBuilder<CartState>.() -> Unit = {}) =
            assertThrownBy<IllegalArgumentException> {
                aggregate<CartState>("example", "cart") {
                    initial { CartState(it.id) }
                    decide<CreateCart>(Creation.REQUIRED) { _, command -> Decision.events(CartCreated(command.owner)) }
                    evolve<CartCreated> { owner = it.owner }
                    extra()
                    retired(*names)
                }
            }
        // A current event name, an event's former name, a command name, a command's former name, a mounted command.
        retiring("example.cart.CartCreated").hasMessageContaining("retires [example.cart.CartCreated]")
        retiring("example.cart.Opened") { alias<CartCreated>("example.cart.Opened") }
        retiring("example.cart.CreateCart")
        retiring("example.cart.NewCart") { alias<CreateCart>("example.cart.NewCart") }
        retiring("example.cart.AddItem") { mount<AddItem>() }
        // A system event, which every model reads.
        retiring(Lifecycle.AGGREGATE_DELETED)
        // Blank, or listed twice, at once or across calls.
        retiring(" ").hasMessageContaining("blank")
        retiring(REMOVED, REMOVED).hasMessageContaining("twice")
        retiring(REMOVED) { retired(REMOVED) }.hasMessageContaining("twice")
    }

    private companion object {
        const val REMOVED = "example.cart.ItemRemoved"
    }
}

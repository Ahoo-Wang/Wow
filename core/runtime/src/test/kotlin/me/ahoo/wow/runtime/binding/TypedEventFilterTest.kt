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

package me.ahoo.wow.runtime.binding

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A handler of each event of a type: its subscription's filter holds the names the models store those events under,
 * so a commit holding none of them completes at admission, before its events are decoded and its state read.
 */
class TypedEventFilterTest {
    sealed interface EntryEvent
    data class Booked(val amount: Long) : EntryEvent
    data class Reversed(val amount: Long) : EntryEvent
    data class Noted(val note: String)
    data class Book(val amount: Long)

    class LedgerState(var balance: Long = 0)

    /** A ledger whose Booked is at revision 2 and was once named Posted, and which retired Archived. */
    private val ledger: AggregateModel<LedgerState> = aggregate<LedgerState>(SHOP, "ledger") {
        initial { LedgerState() }
        decide<Book> { _, body -> Decision.events(Booked(body.amount)) }
        evolve<Booked>(revision = 2) { balance += it.amount }
        upgrade<Booked>(from = 1) { it }
        alias<Booked>(POSTED)
        evolve<Reversed> { balance -= it.amount }
        evolve<Noted> { }
        retired(ARCHIVED)
    }

    private fun stream(vararg events: Pair<String, Int>) = EventStreamRecord(
        ref = ledger.type.ref(TENANT, "l-1"),
        version = 1,
        commandId = "c-1",
        requestId = "r-1",
        commandType = "$SHOP.ledger.Book",
        commitTime = 1L,
        events = events.map { (name, revision) -> EventRecord(name, revision, "{}") },
        scope = Scope.NONE,
    )

    @Test
    fun `a sealed parent's filter should take each of its events by every name, and no other event`() {
        val filter = handlerFilter(SHOP, listOf(ledger), EntryEvent::class.java)
        filter.events.assert().containsExactlyInAnyOrder(BOOKED, POSTED, REVERSED)
        filter.matches(stream(BOOKED to 2)).assert().isTrue()
        filter.matches(stream(REVERSED to 1)).assert().isTrue()
        filter.matches(stream(NOTED to 1)).assert().isFalse()
        filter.matches(stream(NOTED to 1, REVERSED to 1)).assert().isTrue()
    }

    @Test
    fun `a concrete event's filter should match it renamed or at an older revision, and not its sibling`() {
        val filter = handlerFilter(SHOP, listOf(ledger), Booked::class.java)
        filter.events.assert().containsExactlyInAnyOrder(BOOKED, POSTED)
        filter.matches(stream(POSTED to 1)).assert().isTrue()
        filter.matches(stream(BOOKED to 1)).assert().isTrue()
        filter.matches(stream(REVERSED to 1)).assert().isFalse()
        // A retired record is skipped, never decoded: left out as the handler would have left it.
        filter.matches(stream(ARCHIVED to 1)).assert().isFalse()
    }

    @Test
    fun `a name the models do not know should still be handed on, for only decoding can judge it`() {
        val filter = handlerFilter(SHOP, listOf(ledger), Booked::class.java)
        filter.matches(stream("$SHOP.ledger.Settled" to 1)).assert().isTrue()
    }

    @Test
    fun `a type none of the models' events is, or none given, should filter no event`() {
        handlerFilter(SHOP, listOf(ledger), Book::class.java).events.assert().isEmpty()
        handlerFilter(SHOP, listOf(ledger), null).events.assert().isEmpty()
        handlerFilter(SHOP, listOf(ledger), null).matches(stream(NOTED to 1)).assert().isTrue()
        // Any event at all: every event of the model, its system ones included, each still matching.
        handlerFilter(SHOP, listOf(ledger), Any::class.java).matches(stream(NOTED to 1)).assert().isTrue()
    }

    @Test
    fun `a commit the handler does not take should move the checkpoint and answer a wait at its hop, unhandled`(): Unit =
        runBlocking {
            val removed = CopyOnWriteArrayList<String>()
            val removals = ProcessorBinding.react(
                "Removals",
                "onRemoved",
                CartState::class.java,
                eventType = ItemsRemoved::class.java,
            ) { event, _ -> removed += "${event.ref.id}@${event.version}" }
            val backends = InMemoryBackends(partitions = PARTITIONS)
            val node = ServiceNode(
                SHOP_SERVICE,
                "node-1",
                "node-1.internal:9090",
                listOf(orderModel(), cartModel()),
                shopManifest(),
                backends,
                LoopbackClusterChannel(),
                options = fastNodeOptions(),
                subscriptions = SubscriptionBindings(mapOf(SHOP to listOf(removals))),
            )
            try {
                node.start()
                val subscription = node.context(SHOP).logGroups.single().subscriptions
                val filter = SubscriptionBindings(mapOf(SHOP to listOf(removals))).subscriptions(node.context(SHOP))
                    .single().filter
                filter.events.assert().containsExactly("$SHOP.cart.ItemsRemoved")
                // The cart's commit holds ItemsAdded alone: completed at admission, its hop answered with nothing.
                val added = node.gateway.execute(
                    ServerCommandRequest(
                        context = SHOP,
                        aggregate = "cart",
                        command = "AddItems",
                        body = CommandBody.of("""{"items":["apple"]}"""),
                        caller = Caller(TENANT, Scope(ownerId = "alice")),
                        aggregateId = "alice",
                        wait = WaitPlan.of(Hop.committed(), Hop.handled("Removals.onRemoved")),
                    ),
                )
                added.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                val partition = Partitioner.partitionOf(CART.ref(TENANT, "alice"), PARTITIONS)
                val head = checkNotNull(backends.opened.getValue(SHOP).log.head(partition))
                eventually {
                    subscription.positions()
                        .single { it.subscription == "Removals.onRemoved" && it.partition == partition }
                        .watermark >= head
                }
                removed.assert().isEmpty()
            } finally {
                node.stop()
            }
        }

    private companion object {
        const val TENANT = "t"
        const val PARTITIONS = 4
        const val BOOKED = "$SHOP.ledger.Booked"
        const val POSTED = "$SHOP.ledger.Posted"
        const val REVERSED = "$SHOP.ledger.Reversed"
        const val NOTED = "$SHOP.ledger.Noted"
        const val ARCHIVED = "$SHOP.ledger.Archived"
    }
}

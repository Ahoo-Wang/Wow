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

package me.ahoo.wow.runtime.command

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.kernel.CommittedBinding
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.transport.EnvelopeCodec
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A saga's command to another context whose rejection no `@OnRejected` handler takes parks the saga's aggregate at
 * once, as one within the saga's own context does: every command a saga sends to another context asks for its
 * rejection's receipt, and the receipts subscription parks the aggregate when no handler takes it, under its own name,
 * its reason naming the command and the rejection. A commit sends no receipt unless an `@OnCommitted` handler takes it.
 */
class CrossContextUnhandledRejectionTest : CommandIntakeFixtures() {
    private val commits = CopyOnWriteArrayList<CommandReceipt.Committed<*>>()

    /** The order saga, deriving a reservation of each placed order, with the [commits] handlers it is given. */
    private fun saga(vararg commits: CommittedBinding<*>) = ProcessorBinding.saga(
        "OrderSaga",
        "onOrderPlaced",
        OrderState::class.java,
        commits = commits.toList(),
        derives = listOf(Reserve::class.java),
    ) { event, _ ->
        event.events.filterIsInstance<OrderPlaced>().map {
            CommandRequest.to(ITEM.ref(event.ref.tenantId, event.ref.id), Reserve(it.quantity))
        }
    }

    /** An `@OnCommitted` handler of the reservation, and no `@OnRejected` one. */
    private val onReserved = CommittedBinding.of("OrderSaga", "onReserved", Reserve::class.java) { committed, _ ->
        commits += committed
        emptyList()
    }

    private val ledger get() = salesBackends.backends(SALES).ledger

    @Test
    fun `a rejection no handler takes should park the saga's aggregate at once, naming the rejection`(): Unit =
        runBlocking {
            stockNode().start()
            salesNode(saga = saga()).start()
            nodes.last { it.service == SALES_SERVICE }.place("o-1", AVAILABLE + 1)
            val order = ORDER.ref(TENANT, "o-1")
            eventually { ledger.isParked(RECEIPTS_SUBSCRIPTION, order) }
            // The command asked for its rejection's receipt, and not for its commit's.
            val wire = CommandMessages.commandOf(EnvelopeCodec.decode(topics.messages(COMMANDS).single().value))
            wire.replyTo!!.executionId().subscription.assert().isEqualTo("OrderSaga.onOrderPlaced")
            wire.replyTo!!.committed.assert().isFalse()
            val receipt = topics.messages(RECEIPTS).single()
            val rejected = CommandMessages.receiptOf(EnvelopeCodec.decode(receipt.value)) as CommandReceipt.Rejected<*>
            ledger.parked(RECEIPTS_SUBSCRIPTION, order)!!.reason.assert().startsWith(
                "${UnhandledReceiptRejectionException::class.java.name}: OrderSaga.onOrderPlaced in sales takes no " +
                    "rejection of stock.item.Reserve: the derived command ${rejected.commandId} on " +
                    "${ITEM.ref(TENANT, "o-1")} was rejected: $INSUFFICIENT Only $AVAILABLE left.",
            )
        }

    @Test
    fun `a commit should send no receipt when no @OnCommitted handler takes it`(): Unit = runBlocking {
        val stockNode = stockNode().also { it.start() }
        salesNode(saga = saga()).start()
        nodes.last { it.service == SALES_SERVICE }.place("o-2", AVAILABLE)
        // The intake's checkpoint moves past the command once it has executed it and replied, if it replies.
        eventually { checkpoint(stockNode, ITEM.ref(TENANT, "o-2")) == 1L }
        stock.decided.assert().hasSize(1)
        topics.messages(RECEIPTS).assert().isEmpty()
        ledger.count(RECEIPTS_SUBSCRIPTION, 10).assert().isEqualTo(0)
    }

    @Test
    fun `a saga with an @OnCommitted handler alone should park on a rejection, and take the commit`(): Unit =
        runBlocking {
            stockNode().start()
            salesNode(saga = saga(onReserved)).start()
            val sales = nodes.last { it.service == SALES_SERVICE }
            sales.place("o-3", AVAILABLE + 1)
            eventually { ledger.isParked(RECEIPTS_SUBSCRIPTION, ORDER.ref(TENANT, "o-3")) }
            sales.place("o-4", 1)
            eventually { commits.size == 1 }
            commits.single().ref.assert().isEqualTo(ITEM.ref(TENANT, "o-4"))
            ledger.isParked(RECEIPTS_SUBSCRIPTION, ORDER.ref(TENANT, "o-4")).assert().isFalse()
        }

    private companion object {
        val RECEIPTS_SUBSCRIPTION = SubscriptionId(SALES, Receipts.nameOf(SALES))
    }
}

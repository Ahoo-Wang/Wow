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
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.runtime.binding.CompensationRejectedException
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.transport.EnvelopeCodec
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A command a saga's receipt handler derives into another context, a compensation or the next step after a commit:
 * it asks for its rejection's receipt under the handler's own execution, never for its commit's. Its rejection is
 * handed to no handler, since a compensation is not compensated, and parks the saga's aggregate at once under the
 * receipts subscription, whichever subscription ran the receipt handler: the receipts subscription, for a rejection
 * from another context, or the saga's own, for a rejection within the context.
 */
class CompensationReceiptsTest : CommandIntakeFixtures() {
    private val rejected = CopyOnWriteArrayList<CommandReceipt.Rejected<*>>()
    private val committed = CopyOnWriteArrayList<CommandReceipt.Committed<*>>()

    private val ledger get() = salesBackends.backends(SALES).ledger

    /** A reservation of more than the stock has, on the stock item `short-<id>`: the inventory rejects it. */
    private fun short(rejected: CommandReceipt<*>) =
        CommandRequest.to(ITEM.ref(TENANT, "short-${rejected.source.id}"), Reserve(AVAILABLE + 1))

    /** An `@OnRejected` handler of [type] that compensates with a reservation the inventory rejects in turn. */
    private fun compensating(type: Class<*>) =
        RejectionBinding.of("OrderSaga", "onRejected", type, derives = listOf(Reserve::class.java)) { receipt, _ ->
            rejected += receipt
            listOf(short(receipt))
        }

    /** The order saga, deriving [command] for each placed order, with [rejections] and [commits]. */
    private fun saga(
        type: Class<*>,
        rejections: List<RejectionBinding<*>> = emptyList(),
        commits: List<CommittedBinding<*>> = emptyList(),
        command: (String) -> CommandRequest<*>,
    ) = ProcessorBinding.saga(
        "OrderSaga",
        "onOrderPlaced",
        OrderState::class.java,
        rejections = rejections,
        commits = commits,
        derives = listOf(type),
    ) { event, _ -> listOf(command(event.ref.id)) }

    /** The commands on the stock topic, by their target's id, with the execution and commit their replyTo names. */
    private fun replies(): Map<String, Pair<String, Boolean>?> = topics.messages(COMMANDS).associate {
        val wire = CommandMessages.commandOf(EnvelopeCodec.decode(it.value))
        wire.ref.id to wire.replyTo?.let { reply -> reply.executionId().subscription to reply.committed }
    }

    @Test
    fun `a compensation another context rejects should park the aggregate under the receipts subscription`(): Unit =
        runBlocking {
            stockNode().start()
            salesNode(
                saga = saga(Reserve::class.java, rejections = listOf(compensating(Reserve::class.java))) {
                    CommandRequest.to(ITEM.ref(TENANT, it), Reserve(AVAILABLE + 1))
                },
            ).start()
            nodes.last { it.service == SALES_SERVICE }.place("o-1", 1)
            val order = ORDER.ref(TENANT, "o-1")
            eventually { ledger.isParked(RECEIPTS_SUBSCRIPTION, order) }
            // The compensation asked for its rejection's receipt under its handler's execution, not its commit's.
            replies()["short-o-1"].assert().isEqualTo("OrderSaga.onRejected" to false)
            val compensation = topics.messages(RECEIPTS).map {
                CommandMessages.receiptOf(EnvelopeCodec.decode(it.value)) as CommandReceipt.Rejected<*>
            }.single { it.ref.id == "short-o-1" }
            ledger.parked(RECEIPTS_SUBSCRIPTION, order)!!.reason.assert().isEqualTo(
                "${CompensationRejectedException::class.java.name}: The command ${compensation.commandId} on " +
                    "${ITEM.ref(TENANT, "short-o-1")} that OrderSaga.onRejected in sales derived was rejected, and a " +
                    "compensation is not compensated: stock.item.Reserve $INSUFFICIENT Only $AVAILABLE left.",
            )
            // Handed to no handler: the @OnRejected handler took the first rejection alone.
            rejected.map { it.ref.id }.assert().containsExactly("o-1")
        }

    @Test
    fun `a compensation of a rejection within the context, rejected in another, should park it there too`(): Unit =
        runBlocking {
            stockNode().start()
            salesNode(
                saga = saga(ConfirmOrder::class.java, rejections = listOf(compensating(ConfirmOrder::class.java))) {
                    CommandRequest.to(ORDER.ref(TENANT, it), ConfirmOrder())
                },
                order = orderModel(confirmable = { false }),
            ).start()
            nodes.last { it.service == SALES_SERVICE }.place("o-2", 1)
            val order = ORDER.ref(TENANT, "o-2")
            // The confirmation is rejected within the context, under the saga's subscription, whose handler
            // compensates in the stock; the stock's rejection of that comes back on the receipts subscription.
            eventually { ledger.isParked(RECEIPTS_SUBSCRIPTION, order) }
            ledger.isParked(SubscriptionId(SALES, "OrderSaga.onOrderPlaced"), order).assert().isFalse()
            ledger.parked(RECEIPTS_SUBSCRIPTION, order)!!.reason.assert()
                .startsWith("${CompensationRejectedException::class.java.name}: ")
                .contains("that OrderSaga.onRejected in sales derived was rejected")
            rejected.map { it.commandType }.assert().containsExactly("sales.order.ConfirmOrder")
        }

    @Test
    fun `a receipt handler's command should ask for no commit receipt, even of a class an @OnCommitted takes`(): Unit =
        runBlocking {
            val stockNode = stockNode().also { it.start() }
            val onReserved = CommittedBinding.of(
                "OrderSaga",
                "onReserved",
                Reserve::class.java,
                derives = listOf(Reserve::class.java),
            ) { receipt, _ ->
                committed += receipt
                listOf(CommandRequest.to(ITEM.ref(TENANT, "next-${receipt.source.id}"), Reserve(1)))
            }
            salesNode(
                saga = saga(Reserve::class.java, commits = listOf(onReserved)) {
                    CommandRequest.to(ITEM.ref(TENANT, it), Reserve(1))
                },
            ).start()
            nodes.last { it.service == SALES_SERVICE }.place("o-3", 1)
            // Both commands are on the stock topic, and the intake has executed (and replied to) each.
            eventually {
                val sent = topics.messages(COMMANDS).size
                sent == 2 && listOf("o-3", "next-o-3").map { ITEM.ref(TENANT, it) }
                    .distinctBy { Partitioner.partitionOf(it, PARTITIONS) }
                    .sumOf { checkpoint(stockNode, it) } == 2L
            }
            replies().assert().isEqualTo(
                mapOf("o-3" to ("OrderSaga.onOrderPlaced" to true), "next-o-3" to ("OrderSaga.onReserved" to false)),
            )
            // One commit receipt, the first command's: the next one's commit is handed to no @OnCommitted handler.
            topics.messages(RECEIPTS).assert().hasSize(1)
            committed.map { it.ref.id }.assert().containsExactly("o-3")
            ledger.count(RECEIPTS_SUBSCRIPTION, 10).assert().isEqualTo(0)
        }

    private companion object {
        val RECEIPTS_SUBSCRIPTION = SubscriptionId(SALES, Receipts.nameOf(SALES))
    }
}

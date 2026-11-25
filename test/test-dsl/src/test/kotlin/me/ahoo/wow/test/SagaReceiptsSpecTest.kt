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

package me.ahoo.wow.test

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test

/** A saga spec's receipts and compensations carry, derive and retry as a node's do (#6963). */
class SagaReceiptsSpecTest {
    data class OrderPlaced(val total: Int)
    data class OrderPaid(val amount: Int)
    data class ReserveStock(val orderId: String, val quantity: Int)
    data class Cancel(val reason: String)

    class Order(val id: String) {
        var total = 0
        var paid = 0
    }

    private val orderModel: AggregateModel<Order> = aggregate("shop", "order") {
        initial { Order(it.id) }
        evolve<OrderPlaced> { total = it.total }
        evolve<OrderPaid> { paid += it.amount }
    }

    private val stock = AggregateType("inventory", "stock")

    /** Reserves the unpaid part of an order, and cancels the order when the inventory refuses. */
    private fun reserving(): ProcessorBinding<Order> {
        val compensate = RejectionBinding.of(
            "OrderSaga",
            "onRejected",
            ReserveStock::class.java,
            derives = listOf(Cancel::class.java),
        ) { rejected, _ -> listOf(CommandRequest.to(rejected.source, Cancel(rejected.error.code))) }
        return ProcessorBinding.saga(
            "OrderSaga",
            "onOrder",
            Order::class.java,
            rejections = listOf(compensate),
            derives = listOf(ReserveStock::class.java),
        ) { event: StateEvent<Order>, _ ->
            val reserve = ReserveStock(event.ref.id, event.state.total - event.state.paid)
            listOf(CommandRequest.to(stock.ref(event.ref.tenantId, "mug"), reserve))
        }
    }

    @Test
    fun `a compensation should be derived in the scope of the rejected command's commit`() =
        sagaSpec(listOf(reserving())) {
            // As a node's receipt (DerivedCommands.Rejected.toReceipt) carries the rejected command's scope, which the
            // compensations are derived in. Moved from review/r1-testkit (DslReviewR1Test).
            givenAggregate(orderModel, "order-1", OrderPlaced(10), scope = Scope("buyer"))
            whenEvent(OrderPaid(1)) {
                val reserve = expectCommandEnvelope<ReserveStock> { scope.assert().isEqualTo(Scope("buyer")) }
                whenRejected(reserve, "insufficient") {
                    expectCommandEnvelope<Cancel> { scope.assert().isEqualTo(Scope("buyer")) }
                }
            }
        }

    @Test
    fun `the rejection of a compensation for a call's rejection should park, as a compensation is not compensated`() {
        // As a node does (SagaReceipts): the compensations of a call's rejection are its @OnRejected handler's, and
        // their own rejection parks, whatever catch-all handler the saga has. Moved from review/r1-testkit.
        val compensate = RejectionBinding.of(
            "OrderSaga",
            "onReserveRejected",
            ReserveStock::class.java,
            derives = listOf(Cancel::class.java),
        ) { rejected, _ -> listOf(CommandRequest.to(rejected.source, Cancel(rejected.error.code))) }
        val anyRejected = RejectionBinding.of<Any>("OrderSaga", "onAnyRejected", null) { _, _ -> emptyList() }
        val saga = ProcessorBinding.saga(
            "OrderSaga",
            "onOrder",
            Order::class.java,
            rejections = listOf(compensate, anyRejected),
            calls = listOf(ReserveStock::class.java),
        ) { event: StateEvent<Order>, context ->
            context.call(CommandRequest.to(stock.ref(event.ref.tenantId, "mug"), ReserveStock(event.ref.id, 1)))
            emptyList()
        }
        sagaSpec(listOf(saga)) {
            answerCalls { rejectedResult(it, "inventory.stock.insufficient") }
            givenAggregate(orderModel, "order-1", OrderPlaced(10))
            whenEvent(OrderPaid(1)) {
                val cancel = expectCommandEnvelope<Cancel>()
                whenRejected(cancel, "closed") { expectParked() }
            }
        }
    }

    /** A failure the saga's @Retry takes for unrecoverable. */
    class BadCode(message: String) : RuntimeException(message)

    @Test
    fun `a failing receipt of another context's command should be retried by no handler's @Retry lists`() {
        // As a node does: a receipt of another context's command is handled by that context's receipts subscription
        // (wow.receipts.<context>), with no handler retry, so the saga's @Retry does not classify it, and BadCode is
        // recoverable there. A receipt of its own context's command stays under it. Moved from review/r1-testkit.
        val retry = HandlerRetry(maxAttempts = 2, unrecoverable = listOf(BadCode::class.java))
        fun saga(target: AggregateType): ProcessorBinding<*> = ProcessorBinding.saga(
            "OrderSaga",
            "onOrder",
            Order::class.java,
            rejections = listOf(
                RejectionBinding.of("OrderSaga", "onRejected", ReserveStock::class.java) { rejected, _ ->
                    throw BadCode(rejected.error.code)
                },
            ),
            retry = retry,
            derives = listOf(ReserveStock::class.java),
        ) { event: StateEvent<Order>, _ ->
            listOf(CommandRequest.to(target.ref(event.ref.tenantId, "mug"), ReserveStock(event.ref.id, 1)))
        }
        sagaSpec(listOf(saga(stock))) {
            givenAggregate(orderModel, "order-1", OrderPlaced(10))
            whenEvent(OrderPaid(1)) { whenRejected(commands.single(), "insufficient") { expectRetried() } }
        }
        sagaSpec(listOf(saga(AggregateType("shop", "shelf")))) {
            givenAggregate(orderModel, "order-1", OrderPlaced(10))
            whenEvent(OrderPaid(1)) { whenRejected(commands.single(), "insufficient") { expectParked() } }
        }
    }
}

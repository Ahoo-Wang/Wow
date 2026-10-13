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

package me.ahoo.wow.example.eshop.ordering.domain.order

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.eshop.inventory.api.stock.CommitReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.ReleaseReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.ReserveStock
import me.ahoo.wow.example.eshop.ordering.api.order.AwaitStockValidation
import me.ahoo.wow.example.eshop.ordering.api.order.CancelOrder
import me.ahoo.wow.example.eshop.ordering.api.order.OrderAwaitingValidation
import me.ahoo.wow.example.eshop.ordering.api.order.OrderCancelled
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderShipped
import me.ahoo.wow.example.eshop.ordering.api.order.OrderSubmitted
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.rejectionContextOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The order-process saga: validation, reservations in the inventory, and the compensation of a refusal. */
class OrderProcessSagaTest {
    private val order = AggregateType("eshop-ordering", "order").ref(TENANT, ORDER)
    private val stock = AggregateType("eshop-inventory", "stock")
    private val saga = OrderProcessSaga()

    /** The order's state: two lines of the mug and one of the cap. */
    private val state = OrderState(ORDER).apply {
        onSourcing(
            OrderSubmitted(
                "alice",
                listOf(
                    OrderItem("mug", BigDecimal.ONE, 2),
                    OrderItem("cap", BigDecimal.TEN, 1),
                    OrderItem("mug", BigDecimal.ONE, 3),
                ),
            ),
        )
    }

    private fun event(vararg events: Any) =
        StateEvent(order, 2, "command-2", "request-2", "eshop-ordering.order.X", 1L, events.toList(), state)

    private fun context(subscription: String, event: StateEvent<OrderState>) =
        ExecutionSagaContext(ExecutionId(subscription, event.ref, event.version), event)

    @Test
    fun `a submitted order should await its stock validation`() {
        val event = event(OrderSubmitted("alice", state.items))
        val command = saga.onOrderSubmitted(event, context("OrderProcessSaga.onOrderSubmitted", event))!!
        command.ref.assert().isEqualTo(order)
        command.body.assert().isEqualTo(AwaitStockValidation)
        saga.onOrderSubmitted(event(OrderAwaitingValidation), context("OrderProcessSaga.onOrderSubmitted", event))
            .assert().isNull()
    }

    @Test
    fun `an order awaiting validation should reserve each product's stock in the inventory`() {
        val event = event(OrderAwaitingValidation)
        val commands = saga.onOrderAwaitingValidation(event, context(RESERVING, event))
        commands.map { it.ref to it.body }.assert().containsExactly(
            stock.ref(TENANT, "mug") to ReserveStock(ORDER, 5),
            stock.ref(TENANT, "cap") to ReserveStock(ORDER, 1),
        )
        // Handling the commit again derives the same requests, which the inventory decides once.
        // A fresh context: the runtime gives each handling its own, which numbers the commands it derives.
        saga.onOrderAwaitingValidation(event, context(RESERVING, event)).map { it.requestId }
            .assert().isEqualTo(commands.map { it.requestId })
        saga.onOrderAwaitingValidation(event(OrderShipped), context(RESERVING, event)).assert().isEmpty()
    }

    @Test
    fun `a cancelled order should release its reservations`() {
        val event = event(OrderCancelled("cap cannot be reserved"))
        saga.onOrderCancelled(event, context("OrderProcessSaga.onOrderCancelled", event)).map { it.ref to it.body }
            .assert().containsExactly(
                stock.ref(TENANT, "mug") to ReleaseReservation(ORDER),
                stock.ref(TENANT, "cap") to ReleaseReservation(ORDER),
            )
        saga.onOrderCancelled(
            event(OrderShipped),
            context("OrderProcessSaga.onOrderCancelled", event)
        ).assert().isEmpty()
    }

    @Test
    fun `a shipped order should commit its reservations`() {
        val event = event(OrderShipped)
        saga.onOrderShipped(event, context("OrderProcessSaga.onOrderShipped", event)).map { it.ref to it.body }
            .assert().containsExactly(
                stock.ref(TENANT, "mug") to CommitReservation(ORDER),
                stock.ref(TENANT, "cap") to CommitReservation(ORDER),
            )
        saga.onOrderShipped(event(OrderAwaitingValidation), context("OrderProcessSaga.onOrderShipped", event))
            .assert().isEmpty()
    }

    @Test
    fun `a reservation the inventory refused should cancel the order`() = runTest {
        val handler = ModelProvider.load().single().processors(Dependencies.NONE).single { it.name == RESERVING }
        val onRejected = handler.rejectionHandlerOf(ReserveStock::class.java)!!
        onRejected.name.assert().isEqualTo("OrderProcessSaga.onReserveRejected")
        val rejected =
            rejected<ReserveStock>("eshop-inventory.stock.ReserveStock", "eshop-inventory.stock.insufficient")
        val cancel = onRejected.invoke(rejected, rejectionContextOf(onRejected, rejected)).single()
        cancel.ref.assert().isEqualTo(order)
        cancel.body.assert().isEqualTo(CancelOrder("cap cannot be reserved: Only 0 of cap are available, not 1."))
        // The same receipt again derives the same request.
        onRejected.invoke(rejected, rejectionContextOf(onRejected, rejected)).single().requestId
            .assert().isEqualTo(cancel.requestId)
    }

    @Test
    fun `a release the inventory refused should need no compensation`() = runTest {
        val handler = ModelProvider.load().single().processors(Dependencies.NONE)
            .single { it.name == "OrderProcessSaga.onOrderCancelled" }
        val onRejected = handler.rejectionHandlerOf(ReleaseReservation::class.java)!!
        onRejected.name.assert().isEqualTo("OrderProcessSaga.onReleaseRejected")
        val rejected =
            rejected<ReleaseReservation>("eshop-inventory.stock.ReleaseReservation", "wow.aggregate.not-found")
        onRejected.invoke(rejected, rejectionContextOf(onRejected, rejected)).assert().isEmpty()
    }

    private fun <C : Any> rejected(type: String, code: String) = CommandRejected<C>(
        commandId = "derived-2",
        commandType = type,
        ref = stock.ref(TENANT, "cap"),
        error = ErrorInfo(code, "Only 0 of cap are available, not 1."),
        causation = Causation("command-2", "eshop-ordering.order/$TENANT/$ORDER@2"),
        executionId = ExecutionId(RESERVING, order, 2),
    )

    private companion object {
        const val TENANT = "tenant"
        const val ORDER = "alice-1"
        const val RESERVING = "OrderProcessSaga.onOrderAwaitingValidation"
    }
}

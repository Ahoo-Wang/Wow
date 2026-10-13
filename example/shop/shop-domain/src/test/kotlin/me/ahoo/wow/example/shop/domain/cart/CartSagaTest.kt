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

package me.ahoo.wow.example.shop.domain.cart

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.shop.api.cart.RemoveCartItem
import me.ahoo.wow.example.shop.api.order.OrderPaid
import me.ahoo.wow.example.shop.domain.order.OrderFixtures
import me.ahoo.wow.example.shop.domain.order.OrderState
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.StartFrom
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** 9.x's `CartSaga` as a V10 `@Saga`: an order placed from the cart removes its products from the owner's cart. */
class CartSagaTest {
    private val order = AggregateType("example", "order").ref("tenant", OrderFixtures.ORDER_ID)

    private fun event(
        events: List<Any> = listOf(
            OrderFixtures.orderCreated
        ),
        owner: String? = "alice",
        space: String? = null
    ) =
        StateEvent(
            order,
            1,
            "command-1",
            "request-1",
            "example.order.CreateOrder",
            1L,
            events,
            OrderState(OrderFixtures.ORDER_ID),
            scope = Scope(owner, space),
        )

    private fun context(event: StateEvent<OrderState>) =
        ExecutionSagaContext(ExecutionId(SUBSCRIPTION, event.ref, event.version), event)

    @Test
    fun `an order created from the cart should remove its products from the owner's cart`() {
        val event = event()
        val command = CartSaga().onOrderCreated(event, context(event))!!
        command.ref.assert().isEqualTo(AggregateType("example", "cart").ref("tenant", "alice"))
        command.body.assert().isEqualTo(RemoveCartItem(setOf("apple", "pear")))
        command.scope.assert().isEqualTo(Scope.NONE)
        command.causation!!.causationId.assert().isEqualTo("example.order/tenant/order-1@1")
        // Handling the commit again derives the same request.
        CartSaga().onOrderCreated(event, context(event))!!.requestId.assert().isEqualTo(command.requestId)
    }

    @Test
    fun `a spaced order should derive a command without the order's space`() {
        val event = event(space = "store-1")
        CartSaga().onOrderCreated(event, context(event))!!.scope.assert().isEqualTo(Scope.NONE)
    }

    @Test
    fun `an order not from the cart, without an owner, or another event should derive nothing`() {
        val notFromCart = event(listOf(OrderFixtures.orderCreated.copy(fromCart = false)))
        CartSaga().onOrderCreated(notFromCart, context(notFromCart)).assert().isNull()
        val anonymous = event(owner = null)
        CartSaga().onOrderCreated(anonymous, context(anonymous)).assert().isNull()
        val paid = event(listOf(OrderPaid(BigDecimal.ONE, paid = false)))
        CartSaga().onOrderCreated(paid, context(paid)).assert().isNull()
    }

    @Test
    fun `the binding index should list the saga's handler`() {
        runTest {
            val handler = ModelProvider.load().single().processors(Dependencies.NONE).single()
            handler.name.assert().isEqualTo(SUBSCRIPTION)
            handler.kind.assert().isEqualTo(FunctionKind.SAGA)
            handler.stateType.assert().isEqualTo(OrderState::class.java)
            handler.startFrom.assert().isEqualTo(StartFrom.END)
            handler.blocking.assert().isFalse()
            val event = event()
            handler.invoke(event, context(event)).single().body.assert()
                .isEqualTo(RemoveCartItem(setOf("apple", "pear")))
        }
    }

    private companion object {
        const val SUBSCRIPTION = "CartSaga.onOrderCreated"
    }
}

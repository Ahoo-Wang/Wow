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

package me.ahoo.wow.example.eshop.basket.domain.basket

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.subscribe.CallFailedException
import me.ahoo.wow.api.subscribe.CallRejectedException
import me.ahoo.wow.example.eshop.basket.api.basket.BasketCheckedOut
import me.ahoo.wow.example.eshop.basket.api.basket.BasketCleared
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItem
import me.ahoo.wow.example.eshop.basket.api.basket.EshopBasketContract
import me.ahoo.wow.example.eshop.ordering.api.order.EshopOrderingContract
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.test.rejectedResult
import me.ahoo.wow.test.sagaSpec
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The checkout saga: a checked-out basket becomes an order in the ordering context, through a synchronous call. */
class BasketCheckoutSagaTest {
    private val basket = EshopBasketContract.Basket.ref("tenant", "alice")
    private val order = EshopOrderingContract.Order.ref("tenant", "alice-1")
    private val handlers = basketCheckoutSagaHandlers(Dependencies.NONE)
    private val checkedOut = BasketCheckedOut("alice-1", listOf(BasketItem("mug", BigDecimal("8.50"), 2)))

    private fun event(vararg events: Any) =
        stateEvent(
            basket,
            BasketState(),
            *events,
            version = 3,
            commandType = "eshop-basket.basket.CheckoutBasket",
            scope = Scope(ownerId = "alice"),
            operator = Operator.of("alice", "tenant"),
        )

    @Test
    fun `a checked-out basket should submit its order to the ordering context synchronously`() = sagaSpec(handlers) {
        whenEvent(event(checkedOut)) {
            expectCalls(SubmitOrder(listOf(OrderItem("mug", BigDecimal("8.50"), 2))))
            expectNoCommand()
            val submit = calls.single()
            submit.ref.assert().isEqualTo(order)
            // In the basket's scope, for its buyer: the order belongs to the buyer (decisions D163, D189).
            submit.scope.assert().isEqualTo(Scope(ownerId = "alice"))
            submit.operator.assert().isEqualTo(Operator.of("alice", "tenant"))
            // Handling the commit again calls with the same request, which the ordering context decides once.
            expectDeterministic()
        }
    }

    @Test
    fun `an order the ordering context refuses should reach the saga as a rejection`() = sagaSpec(handlers) {
        answerCalls { rejectedResult(it, EshopOrderingContract.Order.Errors.WRONG_STATUS) }
        whenEvent(event(checkedOut)) {
            expectError<CallRejectedException> {
                code.assert().isEqualTo(
                    EshopOrderingContract.Order.Errors.WRONG_STATUS
                )
            }
        }
    }

    @Test
    fun `an ordering context that is down should fail the handling`() = sagaSpec(handlers) {
        answerCalls { throw CallFailedException("eshop-ordering is down") }
        whenEvent(event(checkedOut)) { expectError<CallFailedException>() }
    }

    @Test
    fun `another event of the basket should submit nothing`() = sagaSpec(handlers) {
        whenEvent(event(BasketCleared)) {
            expectNoCommand()
            expectNoCall()
        }
    }
}

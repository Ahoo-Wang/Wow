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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.example.eshop.basket.api.basket.BasketCheckedOut
import me.ahoo.wow.example.eshop.basket.api.basket.BasketCleared
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.test.sagaSpec
import me.ahoo.wow.kernel.test.stateEvent
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The checkout saga: a checked-out basket becomes an order in the ordering context. */
class BasketCheckoutSagaTest {
    private val basket = AggregateType("eshop-basket", "basket").ref("tenant", "alice")
    private val handlers = basketCheckoutSagaHandlers(Dependencies.NONE)

    private fun event(vararg events: Any) =
        stateEvent(
            basket,
            BasketState("alice"),
            *events,
            version = 3,
            commandType = "eshop-basket.basket.CheckoutBasket",
            scope = Scope(ownerId = "alice"),
        )

    @Test
    fun `a checked-out basket should submit its order to the ordering context`() = sagaSpec(handlers) {
        whenEvent(event(BasketCheckedOut("alice-1", listOf(BasketItem("mug", BigDecimal("8.50"), 2))))) {
            val submit = expectCommand(
                SubmitOrder(listOf(OrderItem("mug", BigDecimal("8.50"), 2))),
                to = AggregateType("eshop-ordering", "order").ref("tenant", "alice-1"),
            )
            // In the basket's scope: the order belongs to its buyer (decision D189).
            submit.scope.assert().isEqualTo(Scope(ownerId = "alice"))
            // Handling the commit again derives the same request, which the ordering context decides once.
            expectDeterministic()
        }
    }

    @Test
    fun `another event of the basket should submit nothing`() = sagaSpec(handlers) {
        whenEvent(event(BasketCleared)) { expectNoCommand() }
    }
}

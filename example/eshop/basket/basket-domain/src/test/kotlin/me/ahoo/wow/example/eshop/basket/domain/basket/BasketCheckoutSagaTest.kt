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
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.eshop.basket.api.basket.BasketCheckedOut
import me.ahoo.wow.example.eshop.basket.api.basket.BasketCleared
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder
import me.ahoo.wow.kernel.ExecutionSagaContext
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The checkout saga: a checked-out basket becomes an order in the ordering context. */
class BasketCheckoutSagaTest {
    private val basket = AggregateType("eshop-basket", "basket").ref("tenant", "alice")

    private fun event(vararg events: Any) = StateEvent(
        basket,
        3,
        "command-3",
        "request-3",
        "eshop-basket.basket.CheckoutBasket",
        1L,
        events.toList(),
        BasketState("alice"),
    )

    private fun context(event: StateEvent<BasketState>) =
        ExecutionSagaContext(ExecutionId("BasketCheckoutSaga.onBasketCheckedOut", event.ref, event.version), event)

    @Test
    fun `a checked-out basket should submit its order to the ordering context`() {
        val event = event(BasketCheckedOut("alice-1", listOf(BasketItem("mug", BigDecimal("8.50"), 2))))
        val command = BasketCheckoutSaga().onBasketCheckedOut(event, context(event))!!
        command.ref.assert().isEqualTo(AggregateType("eshop-ordering", "order").ref("tenant", "alice-1"))
        command.body.assert().isEqualTo(SubmitOrder("alice", listOf(OrderItem("mug", BigDecimal("8.50"), 2))))
        // Handling the commit again derives the same request, which the ordering context decides once.
        BasketCheckoutSaga().onBasketCheckedOut(event, context(event))!!.requestId.assert().isEqualTo(command.requestId)
    }

    @Test
    fun `another event of the basket should submit nothing`() {
        val event = event(BasketCleared)
        BasketCheckoutSaga().onBasketCheckedOut(event, context(event)).assert().isNull()
    }
}

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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.eshop.basket.api.basket.BasketCheckedOut
import me.ahoo.wow.example.eshop.ordering.api.OrderingContext
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder

/**
 * Submits a checked-out basket to the ordering context as an order, the classic eShop's checkout: the command goes to
 * the ordering context's command topic, `wow.eshop-ordering.commands`, and the saga's hop is done once the broker has
 * it. Its id is fixed by the basket's commit, so handling the commit again sends the same request, which the ordering
 * context decides once.
 *
 * The buyer goes in the command's body: a saga's command carries no caller scope, so the order cannot take its owner
 * from the basket's (see `example/eshop/DX-GAPS.md`).
 */
@Saga
class BasketCheckoutSaga {
    @OnEvent
    fun onBasketCheckedOut(event: StateEvent<BasketState>, context: SagaContext): Command<SubmitOrder>? {
        val checkedOut = event.events.filterIsInstance<BasketCheckedOut>().firstOrNull() ?: return null
        return context.command(
            ORDER.ref(event.ref.tenantId, checkedOut.orderId),
            SubmitOrder(
                buyerId = event.ref.id,
                items = checkedOut.items.map { OrderItem(it.productId, it.unitPrice, it.quantity) },
            ),
        )
    }

    private companion object {
        val ORDER = AggregateType(OrderingContext.NAME, OrderingContext.ORDER)
    }
}

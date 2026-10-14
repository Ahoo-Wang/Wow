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
 * Submits a checked-out basket to the ordering context as an order, the classic eShop's checkout, synchronously
 * (decision D193): the checkout is done only once the ordering context has the order, so the saga calls the ordering
 * service's command gateway (`wow.calls.contexts.eshop-ordering.url`) instead of sending to its command topic, the
 * default for the rest of the shop (decision D33). The order's number is the checkout's (`alice-1`), fixed by the
 * basket's commit, and the request id comes from the commit too, so handling the commit again, after a retry or a
 * replay, is the same request: the ordering context decides it once and answers with the same order.
 *
 * The order belongs to the buyer: the call carries the basket's scope, whose owner is its buyer, and the buyer as its
 * operator (decisions D163, D189), which the ordering service trusts because the basket service presents its service
 * credential. While the ordering service is down the call fails: the saga is retried within its subscription's budget,
 * then the basket parks until an operator resumes it.
 */
@Saga
class BasketCheckoutSaga {
    @OnEvent
    suspend fun onBasketCheckedOut(event: StateEvent<BasketState>, context: SagaContext): List<Command<*>> {
        val checkedOut = event.events.filterIsInstance<BasketCheckedOut>().firstOrNull() ?: return emptyList()
        context.call(
            context.command(
                ORDER.ref(event.ref.tenantId, checkedOut.orderId),
                SubmitOrder(checkedOut.items.map { OrderItem(it.productId, it.unitPrice, it.quantity) }),
            ),
        )
        return emptyList()
    }

    private companion object {
        val ORDER = AggregateType(OrderingContext.NAME, OrderingContext.ORDER)
    }
}

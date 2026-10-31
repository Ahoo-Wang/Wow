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

import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.annotation.Sends
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateTypedEvent
import me.ahoo.wow.example.eshop.basket.api.basket.BasketCheckedOut
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder

/**
 * Submits a checked-out basket to the ordering context as an order, the classic eShop's checkout, synchronously:
 * the checkout is done only once the ordering context has the order, so the saga calls the ordering
 * service's command gateway (`wow.peers.eshop-ordering.url`) instead of sending to its command topic, the
 * default for the rest of the shop. The call names no order id: the ordering numbers the order it creates
 * (`SO-261031-42`, its order numbers' generator), and the call's request id comes from the basket's commit, so
 * handling the commit again, after a retry or a replay, is the same request: the ordering context decides it once
 * and answers with the same order and number, without taking another (decision D191).
 *
 * The order belongs to the buyer: the call carries the basket's scope, whose owner is its buyer, and the buyer as its
 * operator, which the ordering service trusts because the call reaches its internal port, which only the platform's
 * own services reach, from the basket service, which names itself. While the ordering service is down the call fails: the saga is retried within its subscription's budget,
 * then the basket parks until an operator retries it.
 *
 * `@Sends(calls)` declares the call: the Manifest lists it, so a wait chain may follow the saga into
 * the ordering context, and the node does not start without the ordering's URL. The saga derives nothing (`Unit`).
 */
@Saga
class BasketCheckoutSaga {
    // region call
    @OnEvent
    @Sends(calls = [SubmitOrder::class])
    suspend fun onBasketCheckedOut(event: StateTypedEvent<BasketState, BasketCheckedOut>, context: SagaContext) {
        val checkedOut = event.event
        context.call(
            CommandRequest.of(SubmitOrder(checkedOut.items.map { OrderItem(it.productId, it.unitPrice, it.quantity) })),
        )
    }
    // endregion
}

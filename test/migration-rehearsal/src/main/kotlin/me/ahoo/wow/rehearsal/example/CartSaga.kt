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

package me.ahoo.wow.rehearsal.example

import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.subscribe.StateEvent

/**
 * The 9.x `CartSaga`: an order created from a cart removes its products from the customer's cart (the order's owner).
 * Its subscription, `CartSaga.onOrderCreated`, is where the 9.x compensation service's unresolved executions of the
 * 9.x `CartSaga.onOrderCreated` are parked by the import (.4).
 */
@Saga
class CartSaga {
    @OnEvent(derives = [RemoveCartItem::class])
    fun onOrderCreated(event: StateEvent<OrderState>): List<CommandRequest<RemoveCartItem>> {
        val owner = event.scope.ownerId ?: return emptyList()
        return event.events.filterIsInstance<OrderCreated>().filter { it.fromCart }.map { created ->
            CommandRequest.to(
                CART.ref(event.ref.tenantId, owner),
                RemoveCartItem(created.items.mapTo(linkedSetOf()) { it.productId }),
            )
        }
    }

    private companion object {
        val CART = me.ahoo.wow.api.aggregate.AggregateType("example", "cart")
    }
}

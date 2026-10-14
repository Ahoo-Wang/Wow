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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnRejected
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.shop.api.ExampleContext
import me.ahoo.wow.example.shop.api.cart.RemoveCartItem
import me.ahoo.wow.example.shop.api.order.OrderCreated
import me.ahoo.wow.example.shop.domain.order.OrderState

/**
 * Removes the ordered products from the customer's cart once an order placed from it is created: 9.x's `CartSaga`
 * as a V10 `@Saga`. Its handler is the subscription `CartSaga.onOrderCreated`.
 *
 * A cart's id is its customer's user id, the owner of the order (decision D56: the stream's scope is the order's),
 * and it is in the order's tenant. The command it derives has a request id fixed by the commit (design 01 §1), so
 * handling the order's commit again does not remove anything twice. It carries the order's owner (decision D189) but
 * not its space: a cart has none, so the cart's scope guard (decision D94) does not refuse it, whatever space the
 * order is in. An order without an owner, or not placed from the cart, derives nothing.
 *
 * The cart may refuse the removal: the customer deleted it, or never had one (decision D153). The order stands all
 * the same, so [onRemoveRejected] takes the rejection and derives nothing to compensate; without it, the order would
 * be parked for this saga until someone resolved it.
 */
@Saga
class CartSaga {
    @OnEvent
    fun onOrderCreated(event: StateEvent<OrderState>, context: SagaContext): Command<RemoveCartItem>? {
        val created = event.events.filterIsInstance<OrderCreated>().firstOrNull() ?: return null
        val owner = event.scope.ownerId
        if (!created.fromCart || owner == null) {
            return null
        }
        return context.command(
            CART.ref(event.ref.tenantId, owner),
            RemoveCartItem(productIds = created.items.mapTo(linkedSetOf()) { it.productId }),
        ).let { it.copy(scope = it.scope.copy(spaceId = null)) }
    }

    /** A cart that is deleted, or does not exist, holds nothing to remove: the order stands as it is. */
    @OnRejected
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant") // Whatever the cart said, the order stands.
    fun onRemoveRejected(rejected: CommandRejected<RemoveCartItem>): Command<*>? = null

    private companion object {
        val CART = AggregateType(ExampleContext.NAME, ExampleContext.CART)
    }
}

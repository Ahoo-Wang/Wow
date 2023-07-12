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

package me.ahoo.wow.example.domain.cart

import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.example.api.cart.RemoveCartItem
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.spring.stereotype.StatelessSaga

@StatelessSaga
class CartSaga {

    /**
     * 下单之后删除购物车相应商品
     */
    @OnEvent
    fun onOrderCreated(orderCreated: OrderCreated): RemoveCartItem? {
        if (!orderCreated.fromCart) {
            return null
        }
        return RemoveCartItem(
            id = orderCreated.customerId,
            productIds = orderCreated.items.map { it.productId }.toSet(),
        )
    }
}

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

package me.ahoo.wow.example.eshop.ordering.domain.order

import me.ahoo.wow.example.eshop.ordering.api.order.OrderPlaced

/** The state of a [Order], changed in place by its events. */
class OrderState(val id: String) {
    var productId: String = ""
        private set

    var quantity: Int = 0
        private set

    fun onSourcing(event: OrderPlaced) {
        productId = event.productId
        quantity = event.quantity
    }
}

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

package me.ahoo.wow.example.api.cart

import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.example.api.cart.CartService.SERVICE_ALIAS
import me.ahoo.wow.example.api.cart.CartService.SERVICE_NAME

@BoundedContext(
    SERVICE_NAME,
    SERVICE_ALIAS,
    aggregates = [
        BoundedContext.Aggregate(CartService.CART_AGGREGATE_NAME, packageScopes = [InitializeCart::class]),
    ],
)
object CartService {
    const val SERVICE_NAME = "cart-example-service"
    const val SERVICE_ALIAS = "test_cart"
    const val CART_AGGREGATE_NAME = "cart"
}

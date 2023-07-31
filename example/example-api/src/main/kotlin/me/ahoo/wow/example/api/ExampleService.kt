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

package me.ahoo.wow.example.api

import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.example.api.ExampleService.CART_AGGREGATE_NAME
import me.ahoo.wow.example.api.ExampleService.ORDER_AGGREGATE_NAME
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.order.CreateOrder

@BoundedContext(
    ExampleService.SERVICE_NAME,
    ExampleService.SERVICE_ALIAS,
    aggregates = [
        BoundedContext.Aggregate(ORDER_AGGREGATE_NAME, packageScopes = [CreateOrder::class]),
        BoundedContext.Aggregate(
            CART_AGGREGATE_NAME,
            tenantId = me.ahoo.wow.api.modeling.TenantId.DEFAULT_TENANT_ID,
            packageScopes = [AddCartItem::class]
        ),
    ],
)
object ExampleService {
    const val SERVICE_NAME = "example-service"
    const val SERVICE_ALIAS = "example"
    const val ORDER_AGGREGATE_NAME = "order"
    const val CART_AGGREGATE_NAME = "cart"

    const val CUSTOMER_ORDER_PREFIX = "customer/{customerId}"
}

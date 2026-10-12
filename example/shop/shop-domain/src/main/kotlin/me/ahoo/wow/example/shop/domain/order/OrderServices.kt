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

package me.ahoo.wow.example.shop.domain.order

import java.math.BigDecimal

/*
 * The services placing an order consults. Products live outside this service; the host provides implementations,
 * which the generated model factory takes as parameters (decision D49) and the binding index finds by type in the
 * host's dependencies (decision D107).
 */

/** How many units of a product are in stock. */
interface InventoryService {
    suspend fun getInventory(productId: String): Int
}

/** A product's current unit price. */
interface PricingService {
    suspend fun getProductPrice(productId: String): BigDecimal
}

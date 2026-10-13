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

import me.ahoo.wow.api.annotation.ErrorCode

/** The codes a [Basket] rejects commands with. */
object BasketRejections {
    @ErrorCode(description = "A basket's quantity is positive, and its price not negative.")
    const val INVALID_ITEM = "eshop-basket.basket.invalid-item"

    @ErrorCode(description = "The basket does not hold the product.", status = 404)
    const val ITEM_NOT_FOUND = "eshop-basket.basket.item-not-found"

    @ErrorCode(description = "An empty basket cannot be checked out.")
    const val EMPTY = "eshop-basket.basket.empty"

    @ErrorCode(description = "A basket belongs to the buyer whose id it has.", status = 403)
    const val NOT_OWNER = "eshop-basket.basket.not-owner"
}

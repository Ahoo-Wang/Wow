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

package me.ahoo.wow.example.inventory.domain.stock

import me.ahoo.wow.api.annotation.ErrorCode

/**
 * The codes a stock rejects a command with, besides the framework's (decision D121). The shop's order saga receives
 * [INSUFFICIENT] in the receipt of a `ReserveStock` it sent, and cancels the order.
 */
object StockRejections {
    @ErrorCode(description = "Fewer units of the product are available than the quantity asked for.")
    const val INSUFFICIENT = "inventory.stock.insufficient"

    @ErrorCode(description = "The quantity is not a positive number of units.")
    const val INVALID_QUANTITY = "inventory.stock.invalid-quantity"

    @ErrorCode(description = "Nothing is reserved for the order.", status = 409)
    const val NO_RESERVATION = "inventory.stock.no-reservation"
}

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

import me.ahoo.wow.api.annotation.ErrorCode

/**
 * The codes an order rejects a command with, besides the framework's. They live beside the aggregate, in the module
 * the compiler processes, which lists them in the context's `errors` of the Manifest (decision D121); clients read
 * them there.
 */
object OrderRejections {
    @ErrorCode(description = "A product's price is no longer the one the customer saw.")
    const val PRICE_CHANGED = "example.order.price-changed"

    @ErrorCode(description = "The order has no item.")
    const val NO_ITEMS = "example.order.no-items"

    @ErrorCode(
        description = "The order's status does not allow the command, such as shipping an order that is not paid.",
        status = 409,
    )
    const val WRONG_STATUS = "example.order.wrong-status"
}

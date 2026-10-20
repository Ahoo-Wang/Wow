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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Rejection

/**
 * Rejection codes as the Manifest, results, receipts and HTTP name them (decision D259): a decide function may reject
 * with the local name of a code of its aggregate (`invalid-item`, a code without a dot, declared within its
 * `@AggregateRoot` class), which the kernel qualifies with the deciding command's aggregate type
 * (`eshop-basket.basket.invalid-item`) before anything else sees it; a code with a dot is full, and stays as it is.
 */
@InternalWowApi
object RejectionCodes {
    /** [code] qualified with [type]: `context.aggregate.code` for a local name, [code] itself when it has a dot. */
    @JvmStatic
    fun qualify(code: String, type: AggregateType): String =
        if ('.' in code) code else "${type.context}.${type.name}.$code"

    /** [rejection] with its code qualified with [type] ([qualify]); [rejection] itself when its code is full. */
    @JvmStatic
    fun qualified(rejection: Rejection, type: AggregateType): Rejection {
        val code = qualify(rejection.code, type)
        return if (code == rejection.code) rejection else Rejection.of(code, rejection.message, rejection.extensions)
    }
}

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
package me.ahoo.wow.example.api.order

import me.ahoo.wow.api.annotation.EntityObject
import java.math.BigDecimal

/**
 * 订单项为实体对象 .
 *
 * @author ahoo wang
 */
@EntityObject
data class OrderItem(
    val id: String,
    val productId: String,
    val price: BigDecimal,
    val quantity: Int
) {
    val totalPrice: BigDecimal
        get() {
            return price.multiply(quantity.toBigDecimal())
        }
}

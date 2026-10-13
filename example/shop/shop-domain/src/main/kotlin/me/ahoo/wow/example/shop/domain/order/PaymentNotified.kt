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

import me.ahoo.wow.api.annotation.MountedCommand
import java.math.BigDecimal

/**
 * A payment provider's callback, in the provider's shape (decision D182): mounted under the order's routes, it is no
 * command of the order's. The server's adapter turns it into the `PayOrder` of the order [merchantOrderId] names, paid
 * [amount] with the payment [transactionId]; the provider knows the order only by that id, which it was given when the
 * customer paid.
 */
@MountedCommand(aggregate = "order", transformsTo = ["example.order.PayOrder"])
data class PaymentNotified(
    val merchantOrderId: String,
    val transactionId: String,
    val amount: BigDecimal,
)

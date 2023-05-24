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

import me.ahoo.wow.api.exception.ErrorCodes
import me.ahoo.wow.api.exception.ErrorInfo
import java.math.BigDecimal

/**
 * PayOrder .
 *
 * @author ahoo wang
 */
data class PayOrder(
    val id: String,
    val paymentId: String,
    val amount: BigDecimal
)

data class OrderPaid(val amount: BigDecimal, val paid: Boolean)
data class OrderOverPaid(val paymentId: String, val overPay: BigDecimal)
data class OrderPayDuplicated(val paymentId: String, override val errorMsg: String) :
    ErrorInfo {
    override val errorCode: String
        get() = ErrorCodes.ILLEGAL_STATE
}

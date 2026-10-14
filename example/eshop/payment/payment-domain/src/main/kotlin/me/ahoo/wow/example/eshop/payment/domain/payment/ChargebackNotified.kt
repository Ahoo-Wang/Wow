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

package me.ahoo.wow.example.eshop.payment.domain.payment

import me.ahoo.wow.api.annotation.MountedCommand

/**
 * The payment gateway's chargeback callback, in the gateway's shape (decision D182): the buyer disputed a payment with
 * their bank, which took the money back. Mounted under the payment's routes, it is no command of the payment's; the
 * payment server's transformer turns it into the `RefundPayment` of the payment [merchantReference] names (the
 * gateway knows a payment by the reference it was charged under, the payment's id), and keeps [disputeId] in the
 * command's headers, so the payment's event stream says which dispute refunded it.
 */
@MountedCommand(aggregate = "payment", transformsTo = ["eshop-payment.payment.RefundPayment"])
data class ChargebackNotified(
    val merchantReference: String,
    val disputeId: String,
    val reason: String,
)

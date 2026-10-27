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

package me.ahoo.wow.example.eshop.payment.server

import me.ahoo.wow.example.eshop.payment.api.payment.EshopPaymentContract
import me.ahoo.wow.example.eshop.payment.api.payment.RefundPayment
import me.ahoo.wow.example.eshop.payment.domain.payment.ChargebackNotified
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.CommandInput
import me.ahoo.wow.runtime.admission.CommandTarget
import me.ahoo.wow.runtime.admission.CommandTransformer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The payment service's command transformers, one per command type they take. */
@Configuration(proxyBeanMethods = false)
class PaymentTransformers {
    @Bean
    fun chargebacks(): CommandTransformer = Chargebacks()
}

/**
 * Adapts the gateway's chargeback callback, [ChargebackNotified], mounted under the payment: the refund of the payment
 * it names. The command's headers keep the dispute ([DISPUTE_ID]), and so does the payment's event stream.
 */
class Chargebacks : CommandTransformer {
    override val input: CommandType = command(ChargebackNotified::class.java)

    override suspend fun transform(input: CommandInput): CommandTarget {
        val chargeback = input.body as ChargebackNotified
        return CommandTarget.of(
            type = command(RefundPayment::class.java),
            aggregateId = chargeback.merchantReference,
            body = RefundPayment("Chargeback ${chargeback.disputeId}: ${chargeback.reason}"),
            headers = input.headers + (DISPUTE_ID to chargeback.disputeId),
        )
    }

    companion object {
        /** The header naming the gateway's dispute a refund comes from. */
        const val DISPUTE_ID: String = "dispute-id"
    }
}

/** The command type of [commandClass] of the payment, named after its class. */
private fun command(commandClass: Class<*>): CommandType =
    CommandType.of(
        "${EshopPaymentContract.Payment.CONTEXT}.${EshopPaymentContract.Payment.NAME}.${commandClass.simpleName}",
        commandClass
    )

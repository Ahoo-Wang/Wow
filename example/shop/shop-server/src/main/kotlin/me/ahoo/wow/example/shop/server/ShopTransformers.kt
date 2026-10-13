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

package me.ahoo.wow.example.shop.server

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.example.shop.api.ExampleContext
import me.ahoo.wow.example.shop.api.customer.ChangePassword
import me.ahoo.wow.example.shop.api.customer.CustomerKeys
import me.ahoo.wow.example.shop.api.customer.RegisterCustomer
import me.ahoo.wow.example.shop.api.order.PayOrder
import me.ahoo.wow.example.shop.domain.customer.ImportCustomer
import me.ahoo.wow.example.shop.domain.order.PaymentNotified
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.CommandInput
import me.ahoo.wow.runtime.admission.CommandTarget
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.runtime.admission.ReservationTargetResolver
import me.ahoo.wow.spring.boot.HostBackends
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The shop's command transformers (decision D182), one per command type they take:
 *
 * - `ChangePassword` sent without an id goes to the customer holding its user name, by the built-in resolver over
 *   the context's reservations (decision D50);
 * - [ImportCustomer], mounted under the customer, becomes the [RegisterCustomer] of a customer of the former system;
 * - [PaymentNotified], a payment provider's callback mounted under the order, becomes the [PayOrder] of its order.
 */
@Configuration(proxyBeanMethods = false)
class ShopTransformers {
    @Bean
    fun changePasswordByUsername(backends: HostBackends): CommandTransformer = ReservationTargetResolver(
        input = command(ExampleContext.CUSTOMER, ChangePassword::class.java),
        aggregate = AggregateType(ExampleContext.NAME, ExampleContext.CUSTOMER),
        namespace = CustomerKeys.USERNAME,
        reservations = requireNotNull(backends.factory.backends(ExampleContext.NAME).reservations) {
            "The shop's backend keeps no reservations."
        },
    ) { input -> (input.body as ChangePassword).username.ifBlank { null } }

    @Bean
    fun customerImporter(): CommandTransformer = CustomerImporter()

    @Bean
    fun paymentCallbacks(): CommandTransformer = PaymentCallbacks()
}

/**
 * Imports a customer of the former system as a new customer, its id generated; the command's headers keep the former
 * id ([LEGACY_ID]), and so does the customer's event stream.
 */
class CustomerImporter : CommandTransformer {
    override val input: CommandType = command(ExampleContext.CUSTOMER, ImportCustomer::class.java)

    override suspend fun transform(input: CommandInput): CommandTarget {
        val imported = input.body as ImportCustomer
        return CommandTarget(
            type = command(ExampleContext.CUSTOMER, RegisterCustomer::class.java),
            aggregateId = input.aggregateId,
            body = RegisterCustomer(imported.username, imported.password),
            headers = input.headers + (LEGACY_ID to imported.legacyId),
        )
    }

    companion object {
        /** The header that names a customer's id in the former system. */
        const val LEGACY_ID: String = "legacy-id"
    }
}

/** Adapts a payment provider's callback: the payment of the order it names. */
class PaymentCallbacks : CommandTransformer {
    override val input: CommandType = command(ExampleContext.ORDER, PaymentNotified::class.java)

    override suspend fun transform(input: CommandInput): CommandTarget {
        val notified = input.body as PaymentNotified
        return CommandTarget(
            type = command(ExampleContext.ORDER, PayOrder::class.java),
            aggregateId = notified.merchantOrderId,
            body = PayOrder(notified.transactionId, notified.amount),
            headers = input.headers,
        )
    }
}

/** The command type of [commandClass] of the shop's [aggregate], named after its class (decision D55). */
private fun command(aggregate: String, commandClass: Class<*>): CommandType =
    CommandType("${ExampleContext.NAME}.$aggregate.${commandClass.simpleName}", commandClass)
